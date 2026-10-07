package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ChangeType;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import com.github.lisama569.versionedconfigreleasestorejava.domain.EntryStatus;
import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseItem;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseState;
import com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackTrigger;
import com.github.lisama569.versionedconfigreleasestorejava.domain.WindowSpec;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigEntryRepository;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ReleaseRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import com.github.lisama569.versionedconfigreleasestorejava.support.Cursors;
import com.github.lisama569.versionedconfigreleasestorejava.support.KeyedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_SWEEP_BATCH = 100;

    /** 同 requestId 状态推进的进程内串行锁（创建/回滚/改期共用），DB 乐观锁为跨进程兜底。 */
    private final KeyedLocks keyedLocks = new KeyedLocks();

    private final ReleaseRepository releaseRepository;
    private final ConfigEntryRepository entryRepository;
    private final ConfigRevisionRepository revisionRepository;
    private final ReleaseCreationTx creationTx;

    public ReleaseService(ReleaseRepository releaseRepository,
                          ConfigEntryRepository entryRepository,
                          ConfigRevisionRepository revisionRepository,
                          ReleaseCreationTx creationTx) {
        this.releaseRepository = releaseRepository;
        this.entryRepository = entryRepository;
        this.revisionRepository = revisionRepository;
        this.creationTx = creationTx;
    }

    // ---------------------------------------------------------------------
    // 创建（幂等，锁-事务边界）
    // ---------------------------------------------------------------------

    public ReleaseView create(CreateReleaseRequest request) {
        if (request == null || isBlank(request.requestId())) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (request.entryKeys() == null || request.entryKeys().isEmpty()) {
            throw new BusinessException(ConflictReason.EMPTY_RELEASE,
                    "release must contain at least one entry");
        }
        List<String> normalizedKeys = normalizeKeys(request.entryKeys());
        WindowSpec window = WindowSpec.fromRequestOrNull(request.window(), Clock.now());
        log.info("createRelease input: requestId={}, keys={}, remark={}, window={}",
                request.requestId(), normalizedKeys, request.remark(), window);

        return keyedLocks.withLock(request.requestId(),
                () -> creationTx.createInTransaction(request, normalizedKeys, window));
    }

    /** 事务化的真正创建逻辑，仅由 {@link ReleaseCreationTx} 在持锁状态下调用。 */
    ReleaseView doCreate(CreateReleaseRequest request, List<String> normalizedKeys,
                         WindowSpec window) {
        Optional<Release> existing = releaseRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Release r = existing.get();
            List<String> existingKeys = r.getItems().stream().map(ReleaseItem::getEntryKey).toList();
            if (!existingKeys.equals(normalizedKeys)
                    || !safeEquals(r.getRemark(), request.remark())
                    || !windowMatches(r, window)) {
                log.warn("createRelease rejected idempotency-mismatch: requestId={}, existingKeys={}, incomingKeys={}",
                        request.requestId(), existingKeys, normalizedKeys);
                throw new BusinessException(ConflictReason.IDEMPOTENCY_KEY_MISMATCH,
                        "requestId " + request.requestId()
                                + " was already used with a different payload");
            }
            log.info("createRelease replayed: requestId={}, state={}", request.requestId(), r.getState());
            return toView(r);
        }

        Release saved;
        try {
            Release release = new Release(request.requestId(), request.remark(), Clock.now());
            if (window != null) {
                release.configureWindow(window.effectiveAt(), window.expireAt(),
                        window.ttlSeconds(), Clock.now());
            }
            release = releaseRepository.saveAndFlush(release);
            for (String key : normalizedKeys) {
                ConfigEntry entry = entryRepository.findByKey(key)
                        .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                                "config key not found: " + key));
                if (entry.getStatus() != EntryStatus.DRAFT) {
                    throw new BusinessException(ConflictReason.ENTRY_NOT_RELEASABLE,
                            "entry is not in releasable state: " + key + " (" + entry.getStatus() + ")");
                }
                release.addItem(new ReleaseItem(release.getSeq(), key, entry.getValue(),
                        entry.getValueVersion()));
            }
            saved = releaseRepository.saveAndFlush(release);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(ConflictReason.IDEMPOTENCY_KEY_MISMATCH,
                    "requestId already exists (concurrent create): " + request.requestId());
        }
        log.info("createRelease accepted: requestId={}, seq={}, itemCount={}, hasWindow={}",
                saved.getRequestId(), saved.getSeq(), saved.getItems().size(), saved.hasWindow());
        return toView(saved);
    }

    private static boolean windowMatches(Release r, WindowSpec w) {
        Instant eff = w == null ? null : w.effectiveAt();
        Instant exp = w == null ? null : w.expireAt();
        Long ttl = w == null ? null : w.ttlSeconds();
        return safeEqualsInstant(r.getEffectiveAt(), eff)
                && safeEqualsInstant(r.getExpireAt(), exp)
                && safeEqualsLong(r.getTtlSeconds(), ttl);
    }

    // ---------------------------------------------------------------------
    // 状态机：submit / publish
    // ---------------------------------------------------------------------

    @Transactional
    public ReleaseView submit(String requestId) {
        log.info("submit input: requestId={}", requestId);
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.PENDING) {
            log.info("submit replayed/no-op: requestId={}, state=PENDING", requestId);
            return toView(release);
        }
        return transition(release, ReleaseState.PENDING);
    }

    @Transactional
    public ReleaseView publish(String requestId) {
        log.info("publish input: requestId={}", requestId);
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.PUBLISHED) {
            log.info("publish replayed/no-op: requestId={}, state=PUBLISHED", requestId);
            return toView(release);
        }
        if (release.getState() != ReleaseState.PENDING) {
            return transition(release, ReleaseState.PUBLISHED);
        }
        return doPublish(release, false);
    }

    /** 生效扫描的事务入口：加载后复用统一发布判定。 */
    ReleaseView doPublishExternal(String requestId, boolean automatic) {
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.PUBLISHED) {
            return toView(release);
        }
        if (release.getState() != ReleaseState.PENDING) {
            return transition(release, ReleaseState.PUBLISHED);
        }
        return doPublish(release, automatic);
    }

    private ReleaseView doPublish(Release release, boolean automatic) {        Instant now = Clock.now();
        if (release.getEffectiveAt() != null && now.isBefore(release.getEffectiveAt())) {
            log.warn("publish rejected not-effective: requestId={}, effectiveAt={}, now={}, automatic={}",
                    release.getRequestId(), release.getEffectiveAt(), now, automatic);
            throw new BusinessException(ConflictReason.WINDOW_NOT_EFFECTIVE,
                    "release is not effective until " + release.getEffectiveAt());
        }
        Instant deadline = release.getExpireAt();
        if (deadline != null && !now.isBefore(deadline)) {
            // 窗口在尚未发布时就已经结束：该发布永远不应再生效
            log.warn("publish rejected window-expired: requestId={}, expireAt={}, now={}",
                    release.getRequestId(), deadline, now);
            throw new BusinessException(ConflictReason.WINDOW_EXPIRED,
                    "release window already expired at " + deadline);
        }
        for (ReleaseItem item : release.getItems()) {
            ConfigEntry entry = entryRepository.findByKey(item.getEntryKey())
                    .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                            "config key not found: " + item.getEntryKey()));
            if (entry.getStatus() != EntryStatus.DRAFT) {
                throw new BusinessException(ConflictReason.ENTRY_NOT_RELEASABLE,
                        "entry is not in releasable state: " + item.getEntryKey());
            }
            item.markPublished(entry.getValueVersion());
        }
        release.transitionTo(ReleaseState.PUBLISHED, now);
        release.materializeTtlDeadline(now);
        Release saved = releaseRepository.saveAndFlush(release);
        log.info("publish accepted: requestId={}, automatic={}, actualExpireAt={}",
                saved.getRequestId(), automatic, saved.actualExpireAt());
        return toView(saved);
    }

    // ---------------------------------------------------------------------
    // 回滚：手动 / 自动共用同一条确定性路径
    // ---------------------------------------------------------------------

    /** 人工回滚。加进程内按发布单分段的锁，横跨整个事务，保证并发触发只有一个真正生效。 */
    public ReleaseView rollback(String requestId) {
        return rollbackWithTrigger(requestId, RollbackTrigger.MANUAL);
    }

    /** 到期扫描调用的自动回滚：复用同一把分段锁与同一事务路径。 */
    public ReleaseView autoRollback(String requestId) {
        return rollbackWithTrigger(requestId, RollbackTrigger.AUTO);
    }

    private ReleaseView rollbackWithTrigger(String requestId, RollbackTrigger trigger) {
        log.info("rollback input: requestId={}, trigger={}", requestId, trigger);
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return keyedLocks.withLock(requestId, () -> {
            try {
                return creationTx.rollbackInTransaction(requestId, trigger);
            } catch (ObjectOptimisticLockingFailureException concurrent) {
                // 并发触发（手动/自动/多实例）落败者：当前事务已回滚。
                // 在独立新事务重读终态，确保所有调用拿到同一个状态与结论。
                log.info("rollback converged after concurrent commit: requestId={}, incomingTrigger={}",
                        requestId, trigger);
                return creationTx.readViewInNewTransaction(requestId);
            }
        });
    }

    /**
     * 真正的回滚事务体。在"按 requestId 的锁 + 事务"内执行：
     * 无论手动还是自动、并发多少次，物理上只有一个调用能把状态从 PUBLISHED 推进到
     * ROLLED_BACK；其余调用在此读到的已是终态，统一走重放并返回<em>同一个</em>视图。
     * 乐观锁冲突异常故意透出事务（事务随之回滚），由外层 {@link #rollbackWithTrigger} 收敛。
     */
    ReleaseView doRollback(String requestId, RollbackTrigger trigger) {
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            log.info("rollback replayed/no-op: requestId={}, state=ROLLED_BACK, actualTrigger={}, incomingTrigger={}",
                    requestId, release.effectiveRollbackTrigger(), trigger);
            return toView(release);
        }
        if (release.getState() != ReleaseState.PUBLISHED) {
            // 复用状态机给出 ILLEGAL_STATE_TRANSITION（DRAFT/PENDING 上回滚非法）
            return transition(release, ReleaseState.ROLLED_BACK);
        }

        try {
            return applyRollback(release, trigger);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 异常透出给事务边界外的 rollbackWithTrigger 做终态收敛；此处仅记录。
            log.info("rollback lost concurrent commit, will converge: requestId={}, incomingTrigger={}",
                    requestId, trigger);
            throw e;
        }
    }

    private ReleaseView applyRollback(Release release, RollbackTrigger trigger) {
        String requestId = release.getRequestId();
        Instant now = Clock.now();
        for (ReleaseItem item : release.getItems()) {
            ConfigEntry entry = entryRepository.findByKey(item.getEntryKey())
                    .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                            "config key not found: " + item.getEntryKey()));
            Long publishedVersion = item.getValueVersionAtPublish();
            long currentVersion = entry.getValueVersion();
            log.info("rollback check: requestId={}, trigger={}, key={}, versionAtPublish={}, currentVersion={}",
                    requestId, trigger, item.getEntryKey(), publishedVersion, currentVersion);
            if (publishedVersion == null || publishedVersion != currentVersion) {
                throw new BusinessException(ConflictReason.ROLLBACK_PRECONDITION_FAILED,
                        "entry changed after publish, refusing silent overwrite: "
                                + item.getEntryKey()
                                + " (publishedVersion=" + publishedVersion
                                + ", currentVersion=" + currentVersion + ")");
            }
            long versionFrom = entry.getValueVersion();
            String oldValue = entry.getValue();
            String restoreValue = item.getValueAtCreate();
            entry.restoreValue(restoreValue, now);
            revisionRepository.save(new ConfigRevision(entry.getSeq(), entry.getKey(),
                    ChangeType.RESTORE, versionFrom, entry.getValueVersion(),
                    oldValue, restoreValue, triggerOperator(trigger, requestId), now));
        }
        release.markRollbackTrigger(trigger);
        release.transitionTo(ReleaseState.ROLLED_BACK, now);
        Release saved = releaseRepository.saveAndFlush(release);
        log.info("rollback accepted: requestId={}, trigger={}", requestId, trigger);
        return toView(saved);
    }

    private static String triggerOperator(RollbackTrigger trigger, String requestId) {
        return trigger == RollbackTrigger.AUTO
                ? "system-auto-rollback:" + requestId
                : "system-rollback:" + requestId;
    }

    // ---------------------------------------------------------------------
    // 时间窗改期 / 取消（到点之前允许，结论确定）
    // ---------------------------------------------------------------------

    public ReleaseView rescheduleWindow(String requestId, WindowRequest window) {
        log.info("rescheduleWindow input: requestId={}, windowReq={}", requestId, window);
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (window == null) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "window body required; call cancel to remove a window");
        }
        return keyedLocks.withLock(requestId,
                () -> creationTx.rescheduleInTransaction(requestId, window));
    }

    public ReleaseView cancelWindow(String requestId) {
        log.info("cancelWindow input: requestId={}", requestId);
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return keyedLocks.withLock(requestId,
                () -> creationTx.cancelWindowInTransaction(requestId));
    }

    ReleaseView doRescheduleWindow(String requestId, WindowRequest request) {
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "release already rolled back, window is immutable: " + requestId);
        }
        Instant now = Clock.now();
        if (release.getState() == ReleaseState.PUBLISHED) {
            Instant deadline = release.actualExpireAt();
            if (deadline != null && !now.isBefore(deadline)) {
                log.warn("reschedule rejected window-expired: requestId={}, expireAt={}, now={}",
                        requestId, deadline, now);
                throw new BusinessException(ConflictReason.WINDOW_EXPIRED,
                        "window already expired at " + deadline + "; only rollback remains");
            }
        }
        WindowSpec spec = WindowSpec.fromRequestOrNull(request, now);
        if (spec == null) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "window must set at least one field; call cancel to remove a window");
        }
        // 已发布的 TTL 窗口已固化为绝对到期点：改期统一以"新的绝对/TTL 定义"重算。
        release.configureWindow(spec.effectiveAt(), spec.expireAt(), spec.ttlSeconds(), now);
        Release saved = releaseRepository.saveAndFlush(release);
        log.info("rescheduleWindow accepted: requestId={}, actualExpireAt={}",
                requestId, saved.actualExpireAt());
        return toView(saved);
    }

    ReleaseView doCancelWindow(String requestId) {
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "release already rolled back, window is immutable: " + requestId);
        }
        Instant now = Clock.now();
        if (release.getState() == ReleaseState.PUBLISHED) {
            Instant deadline = release.actualExpireAt();
            if (deadline != null && !now.isBefore(deadline)) {
                log.warn("cancel rejected window-expired: requestId={}, expireAt={}, now={}",
                        requestId, deadline, now);
                throw new BusinessException(ConflictReason.WINDOW_EXPIRED,
                        "window already expired at " + deadline + "; only rollback remains");
            }
        }
        release.clearWindow(now);
        Release saved = releaseRepository.saveAndFlush(release);
        log.info("cancelWindow accepted: requestId={}, nowHasWindow={}", requestId, saved.hasWindow());
        return toView(saved);
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    /** 供跨事务终态收敛重读使用（必须在独立新事务内调用）。 */
    ReleaseView getFreshView(String requestId) {
        return toView(requireRelease(requestId));
    }

    @Transactional(readOnly = true)
    public ReleaseView get(String requestId) {
        return toView(requireRelease(requestId));
    }

    @Transactional(readOnly = true)
    public CursorPage<ReleaseView> list(String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        List<Release> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = releaseRepository.findFirstPage(PageRequest.of(0, pageSize + 1));
        } else {
            long[] c = Cursors.decode(cursor);
            rows = releaseRepository.findPageAfter(Instant.ofEpochMilli(c[0]), c[1],
                    PageRequest.of(0, pageSize + 1));
        }
        boolean hasNext = rows.size() > pageSize;
        List<Release> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasNext) {
            Release last = page.get(pageSize - 1);
            nextCursor = Cursors.encode(last.getCreatedAt().toEpochMilli(), last.getSeq());
        }
        return new CursorPage<>(page.stream().map(ReleaseService::toView).toList(), nextCursor);
    }

    /**
     * 时间窗维度分页。游标在普通 {@code "<millis>_<seq>"} 基础上额外携带遍历起点的
     * seq 上界（{@code "<millis>_<seq>_<maxSeq>"}），把本次遍历的发布单宇宙冻结，
     * 使持续写入下既不重复也不遗漏。
     */
    @Transactional(readOnly = true)
    public CursorPage<ReleaseView> listWindow(WindowBucket bucket, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        Instant now = Clock.now();
        List<Release> rows;
        long maxSeq;
        Instant afterInstant;
        long afterSeq;

        if (cursor == null || cursor.isBlank()) {
            maxSeq = releaseRepository.findMaxSeq();
            afterInstant = null;
            afterSeq = 0L;
        } else {
            long[] c = decodeWindowCursor(cursor);
            afterInstant = Instant.ofEpochMilli(c[0]);
            afterSeq = c[1];
            maxSeq = c[2];
        }

        PageRequest pageable = PageRequest.of(0, pageSize + 1);
        rows = switch (bucket) {
            case EXPIRING -> (afterInstant == null)
                    ? releaseRepository.findActiveWindowFirstPage(ReleaseState.PUBLISHED, pageable)
                    : releaseRepository.findActiveWindowPageAfter(ReleaseState.PUBLISHED,
                            afterInstant, afterSeq, maxSeq, pageable);
            case OVERDUE -> (afterInstant == null)
                    ? releaseRepository.findDueRollbacks(ReleaseState.PUBLISHED, now, pageable)
                    : releaseRepository.findOverduePageAfter(ReleaseState.PUBLISHED, now,
                            afterInstant, afterSeq, maxSeq, pageable);
            case AUTO_ROLLED_BACK -> (afterInstant == null)
                    ? releaseRepository.findAutoRolledBackFirstPage(ReleaseState.ROLLED_BACK,
                            RollbackTrigger.AUTO, pageable)
                    : releaseRepository.findAutoRolledBackPageAfter(ReleaseState.ROLLED_BACK,
                            RollbackTrigger.AUTO, afterSeq, pageable);
        };

        boolean hasNext = rows.size() > pageSize;
        List<Release> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasNext) {
            Release last = page.get(pageSize - 1);
            Instant orderInstant = bucket == WindowBucket.AUTO_ROLLED_BACK
                    ? last.getCreatedAt()
                    : last.getExpireAt();
            nextCursor = encodeWindowCursor(orderInstant.toEpochMilli(), last.getSeq(), maxSeq);
        }
        log.info("windowPage decided: bucket={}, inputCursor={}, pageSize={}, returned={}, hasNext={}, frozenMaxSeq={}",
                bucket, cursor == null ? "<first>" : cursor, pageSize, page.size(), hasNext, maxSeq);
        return new CursorPage<>(page.stream().map(ReleaseService::toView).toList(), nextCursor);
    }

    // ---------------------------------------------------------------------
    // 到期 / 生效扫描
    // ---------------------------------------------------------------------

    /**
     * 扫描并自动回滚所有已到期的在途发布单。逐个在独立事务 + 分段锁内处理，
     * 与人工回滚/重复触发/多实例并发共用同一条确定性路径。
     */
    public SweepResult sweepExpiredReleases() {
        return sweepExpiredReleases(DEFAULT_SWEEP_BATCH);
    }

    public SweepResult sweepExpiredReleases(int batchSize) {
        Instant now = Clock.now();
        int size = batchSize <= 0 ? DEFAULT_SWEEP_BATCH : Math.min(batchSize, MAX_LIMIT);
        List<Release> due = releaseRepository.findDueRollbacks(ReleaseState.PUBLISHED, now,
                PageRequest.of(0, size));
        log.info("sweepExpired input: now={}, candidates={}", now,
                due.stream().map(Release::getRequestId).toList());
        List<String> targets = new ArrayList<>();
        int succeeded = 0;
        int replayed = 0;
        int failed = 0;
        for (Release candidate : due) {
            String id = candidate.getRequestId();
            targets.add(id);
            try {
                ReleaseView view = autoRollback(id);
                if ("ROLLED_BACK".equals(view.state())
                        && RollbackTrigger.AUTO.name().equals(view.rollbackTrigger())) {
                    succeeded++;
                } else {
                    replayed++;
                }
            } catch (BusinessException e) {
                // 前置条件失败（发布后被改过）等：保留 PUBLISHED，留待人工介入，下次扫描再判。
                failed++;
                log.warn("sweepExpired item failed: requestId={}, reason={}, message={}",
                        id, e.getReason(), e.getMessage());
            }
        }
        SweepResult result = new SweepResult(due.size(), succeeded, replayed, failed, targets);
        log.info("sweepExpired decision: {}", result);
        return result;
    }

    /**
     * 扫描到达生效时刻、仍在 PENDING 的发布单，自动推进到 PUBLISHED。
     * 已被人工推进/拒绝的情况由 {@link #doPublish} 的统一判定给出确定结论。
     */
    public SweepResult sweepEffectiveReleases() {
        return sweepEffectiveReleases(DEFAULT_SWEEP_BATCH);
    }

    public SweepResult sweepEffectiveReleases(int batchSize) {
        Instant now = Clock.now();
        int size = batchSize <= 0 ? DEFAULT_SWEEP_BATCH : Math.min(batchSize, MAX_LIMIT);
        List<Release> due = releaseRepository.findDuePublishes(ReleaseState.PENDING, now,
                PageRequest.of(0, size));
        log.info("sweepEffective input: now={}, candidates={}", now,
                due.stream().map(Release::getRequestId).toList());
        List<String> targets = new ArrayList<>();
        int succeeded = 0;
        int replayed = 0;
        int failed = 0;
        for (Release candidate : due) {
            String id = candidate.getRequestId();
            targets.add(id);
            try {
                ReleaseView view = creationTx.publishInTransaction(id, true);
                if ("PUBLISHED".equals(view.state())) {
                    succeeded++;
                } else {
                    replayed++;
                }
            } catch (BusinessException e) {
                failed++;
                log.warn("sweepEffective item failed: requestId={}, reason={}, message={}",
                        id, e.getReason(), e.getMessage());
            }
        }
        SweepResult result = new SweepResult(due.size(), succeeded, replayed, failed, targets);
        return result;
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    private ReleaseView transition(Release release, ReleaseState target) {
        return transition(release, target, Clock.now());
    }

    private ReleaseView transition(Release release, ReleaseState target, Instant now) {
        if (!release.canTransitionTo(target)) {
            log.warn("transition rejected: requestId={}, from={}, to={}",
                    release.getRequestId(), release.getState(), target);
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "illegal transition " + release.getState() + " -> " + target
                            + " for release " + release.getRequestId());
        }
        try {
            release.transitionTo(target, now);
            Release saved = releaseRepository.saveAndFlush(release);
            log.info("transition accepted: requestId={}, to={}", saved.getRequestId(), target);
            return toView(saved);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "release state changed concurrently: " + release.getRequestId());
        }
    }

    private Release requireRelease(String requestId) {
        return releaseRepository.findByRequestId(requestId)
                .orElseThrow(() -> new BusinessException(ConflictReason.RELEASE_NOT_FOUND,
                        "release not found: " + requestId));
    }

    private static List<String> normalizeKeys(List<String> keys) {
        Set<String> dedup = new LinkedHashSet<>();
        for (String k : keys) {
            if (isBlank(k)) {
                throw new IllegalArgumentException("entry key must not be blank");
            }
            dedup.add(k.trim());
        }
        return new ArrayList<>(dedup);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean safeEqualsInstant(Instant a, Instant b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean safeEqualsLong(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }

    static String encodeWindowCursor(long orderEpochMilli, long seq, long maxSeq) {
        return orderEpochMilli + "_" + seq + "_" + maxSeq;
    }

    /** @return [orderEpochMilli, seq, maxSeq] */
    static long[] decodeWindowCursor(String cursor) {
        String[] parts = cursor.split("_");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed window cursor: " + cursor);
        }
        try {
            long millis = Long.parseLong(parts[0]);
            long seq = Long.parseLong(parts[1]);
            long maxSeq = Long.parseLong(parts[2]);
            if (millis < 0 || seq < 0 || maxSeq < 0) {
                throw new IllegalArgumentException("malformed window cursor: " + cursor);
            }
            return new long[] {millis, seq, maxSeq};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed window cursor: " + cursor, e);
        }
    }

    public static ReleaseView toView(Release r) {
        Long anchorRevision = null;
        for (ReleaseItem item : r.getItems()) {
            if (item.getValueVersionAtPublish() != null) {
                anchorRevision = item.getValueVersionAtPublish();
                break;
            }
        }
        String trigger = r.getState() == ReleaseState.ROLLED_BACK
                ? r.effectiveRollbackTrigger().name()
                : null;
        return new ReleaseView(r.getSeq(), r.getRequestId(), r.getState().name(),
                r.getItems().stream().map(ReleaseItem::getEntryKey).toList(),
                r.getRemark(), anchorRevision, Math.max(r.getFormatVersion(), 1),
                r.getCreatedAt(), r.getUpdatedAt(),
                r.getEffectiveAt(), r.getExpireAt(), r.getTtlSeconds(),
                r.actualExpireAt(), trigger);
    }
}
