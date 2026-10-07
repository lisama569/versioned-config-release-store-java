package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateWindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowQueryStatus;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ChangeType;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseItem;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseState;
import com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackReason;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigEntryRepository;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ReleaseRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import com.github.lisama569.versionedconfigreleasestorejava.support.Cursors;
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
    /** 单轮到期清扫的最大处理量，避免在途定时发布单很多时单轮过长。 */
    private static final int SWEEP_BATCH_SIZE = 500;
    /** "即将到期"查询的默认时间范围（秒）。 */
    private static final long DEFAULT_EXPIRING_SOON_SECONDS = 3600;

    /** 同 requestId 创建临界区的进程内串行锁（数据库唯一索引为跨进程兜底）。 */
    private final com.github.lisama569.versionedconfigreleasestorejava.support.KeyedLocks
            createLocks = new com.github.lisama569.versionedconfigreleasestorejava.support.KeyedLocks();

    private final ReleaseRepository releaseRepository;
    private final ConfigEntryRepository entryRepository;
    private final ConfigRevisionRepository revisionRepository;
    private final ReleaseCreationTx creationTx;
    private final ReleaseRollbackTx rollbackTx;

    public ReleaseService(ReleaseRepository releaseRepository,
                          ConfigEntryRepository entryRepository,
                          ConfigRevisionRepository revisionRepository,
                          ReleaseCreationTx creationTx,
                          ReleaseRollbackTx rollbackTx) {
        this.releaseRepository = releaseRepository;
        this.entryRepository = entryRepository;
        this.revisionRepository = revisionRepository;
        this.creationTx = creationTx;
        this.rollbackTx = rollbackTx;
    }

    /**
     * 创建发布单（幂等）。
     * 重复 requestId：条目集合与备注一致 -> 返回首次结果；不一致 -> IDEMPOTENCY_KEY_MISMATCH。
     *
     * <p>本方法故意<em>不</em>开启事务：先按 requestId 加进程锁，再调用
     * {@link ReleaseCreationTx#createInTransaction} 的事务方法。锁在事务外，
     * 因此锁释放时事务已提交，后来者必读到前者结果。
     */
    public ReleaseView create(CreateReleaseRequest request) {
        if (request == null || isBlank(request.requestId())) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (request.entryKeys() == null || request.entryKeys().isEmpty()) {
            throw new BusinessException(ConflictReason.EMPTY_RELEASE,
                    "release must contain at least one entry");
        }
        List<String> normalizedKeys = normalizeKeys(request.entryKeys());
        log.info("createRelease input: requestId={}, keys={}, remark={}, effectiveFrom={}, "
                + "effectiveUntil={}, effectiveForSeconds={}",
                request.requestId(), normalizedKeys, request.remark(),
                request.effectiveFrom(), request.effectiveUntil(), request.effectiveForSeconds());

        // 同一 requestId 的"查重 -> 插入"临界区串行化：第一个创建者完成事务后，
        // 后来者要么命中既有单走幂等重放，要么被唯一索引兜底，绝不双生效。
        return createLocks.withLock(request.requestId(),
                () -> creationTx.createInTransaction(request, normalizedKeys));
    }

    /** 事务化的真正创建逻辑，仅由 {@link ReleaseCreationTx} 在持锁状态下调用。 */
    ReleaseView doCreate(CreateReleaseRequest request, List<String> normalizedKeys) {
        WindowSpec window = resolveWindow(request.effectiveFrom(), request.effectiveUntil(),
                request.effectiveForSeconds());
        Optional<Release> existing = releaseRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Release r = existing.get();
            List<String> existingKeys = r.getItems().stream().map(ReleaseItem::getEntryKey).toList();
            if (!existingKeys.equals(normalizedKeys)
                    || !safeEquals(r.getRemark(), request.remark())
                    || !sameWindow(r, window)) {
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
            release.applyWindow(window.windowStart(), window.windowEnd(), window.durationSeconds());
            release = releaseRepository.saveAndFlush(release);
            for (String key : normalizedKeys) {
                ConfigEntry entry = entryRepository.findByKey(key)
                        .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                                "config key not found: " + key));
                if (entry.getStatus() != com.github.lisama569.versionedconfigreleasestorejava.domain.EntryStatus.DRAFT) {
                    throw new BusinessException(ConflictReason.ENTRY_NOT_RELEASABLE,
                            "entry is not in releasable state: " + key + " (" + entry.getStatus() + ")");
                }
                release.addItem(new ReleaseItem(release.getSeq(), key, entry.getValue(),
                        entry.getValueVersion()));
            }
            saved = releaseRepository.saveAndFlush(release);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 跨进程并发使用同一 requestId：唯一索引 uk_release_request 兜底
            throw new BusinessException(ConflictReason.IDEMPOTENCY_KEY_MISMATCH,
                    "requestId already exists (concurrent create): " + request.requestId());
        }
        log.info("createRelease accepted: requestId={}, seq={}, itemCount={}",
                saved.getRequestId(), saved.getSeq(), saved.getItems().size());
        return toView(saved);
    }

    /**
     * DRAFT -> PENDING。
     * 幂等重放：已处于 PENDING 时原样返回；PUBLISHED/ROLLED_BACK 上提交属于非法迁移。
     */
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

    /**
     * PENDING -> PUBLISHED。
     * 幂等重放：已处于 PUBLISHED 时原样返回，不重复生效；
     * DRAFT（漏提交）与 ROLLED_BACK（终态）上发布均非法。
     */
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

        Instant now = Clock.now();
        // 发布瞬间冻结每个条目的业务版本，作为后续回滚前置条件。
        for (ReleaseItem item : release.getItems()) {
            ConfigEntry entry = entryRepository.findByKey(item.getEntryKey())
                    .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                            "config key not found: " + item.getEntryKey()));
            if (entry.getStatus() != com.github.lisama569.versionedconfigreleasestorejava.domain.EntryStatus.DRAFT) {
                throw new BusinessException(ConflictReason.ENTRY_NOT_RELEASABLE,
                        "entry is not in releasable state: " + item.getEntryKey());
            }
            item.markPublished(entry.getValueVersion());
        }
        // 仅声明时长的窗口在发布瞬间结算出确定终点
        release.settleWindowOnPublish(now);
        return transition(release, ReleaseState.PUBLISHED, now);
    }

    /**
     * 手动回滚入口（PUBLISHED -> ROLLED_BACK）。
     *
     * <p>本方法故意<em>不</em>开启事务：真正的临界区在
     * {@link ReleaseRollbackTx#rollbackInTransaction}。若与自动回滚/并发回滚
     * 在提交时发生乐观锁冲突，本方法用全新事务重试；重试时并发获胜方已经提交，
     * 本调用按"已回滚"幂等重放——因此无论多少来源并发触发，回滚只真正生效一次，
     * 且所有调用得到同一状态与同一结论。
     */
    public ReleaseView rollback(String requestId) {
        log.info("rollback input: requestId={} (manual)", requestId);
        return rollbackWithRetry(requestId, RollbackReason.MANUAL);
    }

    /**
     * 到期自动回滚入口。仅当窗口终点已到（windowEnd <= now）才执行；
     * 未到期或无时间窗时以 {@code WINDOW_NOT_EXPIRED} 明确拒绝；
     * 已回滚（无论谁先触发）则幂等重放同一结果。
     */
    public ReleaseView rollbackAuto(String requestId) {
        log.info("rollback input: requestId={} (auto-expired)", requestId);
        return rollbackWithRetry(requestId, RollbackReason.AUTO_EXPIRED);
    }

    private ReleaseView rollbackWithRetry(String requestId, RollbackReason reason) {
        final int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                return rollbackTx.rollbackInTransaction(requestId, reason);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.warn("rollback concurrent-conflict exhausted: requestId={}, attempts={}",
                            requestId, attempt);
                    throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                            "release state changed concurrently: " + requestId);
                }
                log.info("rollback retry after concurrent conflict: requestId={}, attempt={}",
                        requestId, attempt);
            } catch (BusinessException e) {
                // 并发回滚的另一种时序：本事务读到对方提交前的 PUBLISHED 状态，
                // 却读到对方提交后的条目版本，误判为"发布后被改过"。
                // 用全新事务重试：若对方已提交回滚，将按"已回滚"幂等重放；
                // 若前置条件失败是真实的（确实被第三方改过），重试后仍确定性地抛出。
                if (e.getReason() != ConflictReason.ROLLBACK_PRECONDITION_FAILED
                        || attempt >= maxAttempts) {
                    throw e;
                }
                log.info("rollback retry after precondition check raced with concurrent "
                        + "rollback: requestId={}, attempt={}", requestId, attempt);
            }
        }
    }

    /**
     * 事务化的回滚核心，仅由 {@link ReleaseRollbackTx} 调用。
     * 乐观锁冲突不在此捕获，原样抛给外层重试。
     */
    ReleaseView doRollback(String requestId, RollbackReason reason) {
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            log.info("rollback replayed/no-op: requestId={}, state=ROLLED_BACK, reason={}",
                    requestId, release.getRollbackReason());
            return toView(release);
        }
        if (reason == RollbackReason.AUTO_EXPIRED) {
            Instant now = Clock.now();
            if (release.getWindowEnd() == null || release.getWindowEnd().isAfter(now)) {
                log.warn("auto-rollback rejected: requestId={}, windowEnd={}, now={}",
                        requestId, release.getWindowEnd(), now);
                throw new BusinessException(ConflictReason.WINDOW_NOT_EXPIRED,
                        "release " + requestId + " has no expired window (windowEnd="
                                + release.getWindowEnd() + ")");
            }
        }
        if (release.getState() != ReleaseState.PUBLISHED) {
            return transition(release, ReleaseState.ROLLED_BACK);
        }

        Instant now = Clock.now();
        String operator = (reason == RollbackReason.AUTO_EXPIRED
                ? "system-auto-rollback:" : "system-rollback:") + requestId;
        for (ReleaseItem item : release.getItems()) {
            ConfigEntry entry = entryRepository.findByKey(item.getEntryKey())
                    .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                            "config key not found: " + item.getEntryKey()));
            Long publishedVersion = item.getValueVersionAtPublish();
            long currentVersion = entry.getValueVersion();
            log.info("rollback check: requestId={}, key={}, versionAtPublish={}, currentVersion={}",
                    requestId, item.getEntryKey(), publishedVersion, currentVersion);
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
                    oldValue, restoreValue, operator, now));
        }
        release.markRollbackReason(reason);
        release.transitionTo(ReleaseState.ROLLED_BACK, now);
        Release saved = releaseRepository.saveAndFlush(release);
        log.info("rollback accepted: requestId={}, reason={}", requestId, reason);
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public ReleaseView get(String requestId) {
        return toView(requireRelease(requestId));
    }

    /**
     * 重新设置生效时间窗（整体替换）。仅允许在终态（ROLLED_BACK）之前；
     * 已发布且仅声明时长时，终点按"当前时刻 + 时长"立即结算。
     * 新终点若已经过去，该单随即进入"已到期未回滚"，由下一轮清扫自动回滚。
     */
    @Transactional
    public ReleaseView updateWindow(String requestId, UpdateWindowRequest request) {
        WindowSpec spec = resolveWindow(request == null ? null : request.effectiveFrom(),
                request == null ? null : request.effectiveUntil(),
                request == null ? null : request.effectiveForSeconds());
        log.info("updateWindow input: requestId={}, effectiveFrom={}, effectiveUntil={}, "
                + "effectiveForSeconds={}", requestId, spec.windowStart(), spec.windowEnd(),
                spec.durationSeconds());
        Release release = requireRelease(requestId);
        requireNotTerminal(release, "update window");
        Instant windowEnd = spec.windowEnd();
        if (windowEnd == null && spec.durationSeconds() != null
                && release.getState() == ReleaseState.PUBLISHED) {
            windowEnd = Clock.now().plusSeconds(spec.durationSeconds());
        }
        release.applyWindow(spec.windowStart(), windowEnd, spec.durationSeconds());
        return saveWindowChange(release, "update");
    }

    /**
     * 取消生效时间窗。幂等：本无时间窗时为 no-op；取消后该单永不参与自动回滚。
     * 仅允许在终态之前。
     */
    @Transactional
    public ReleaseView clearWindow(String requestId) {
        log.info("clearWindow input: requestId={}", requestId);
        Release release = requireRelease(requestId);
        requireNotTerminal(release, "clear window");
        release.applyWindow(null, null, null);
        return saveWindowChange(release, "clear");
    }

    private void requireNotTerminal(Release release, String action) {
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            log.warn("window change rejected: requestId={}, state=ROLLED_BACK, action={}",
                    release.getRequestId(), action);
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "cannot " + action + " on a rolled-back release: " + release.getRequestId());
        }
    }

    private ReleaseView saveWindowChange(Release release, String action) {
        try {
            Release saved = releaseRepository.saveAndFlush(release);
            log.info("window {} accepted: requestId={}, windowEnd={}",
                    action, saved.getRequestId(), saved.getWindowEnd());
            return toView(saved);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "release state changed concurrently: " + release.getRequestId());
        }
    }

    /**
     * 按时间窗维度分页查询。三种口径的过滤条件不同，但排序键统一为
     * (createdAt, seq)（与主列表一致）：新写入的发布单排序键必然更大，
     * 因此持续写入下翻页不重复、不遗漏。
     */
    @Transactional(readOnly = true)
    public CursorPage<ReleaseView> listByWindow(String status, String cursor, int limit,
                                                Long withinSeconds) {
        WindowQueryStatus queryStatus = WindowQueryStatus.parse(status);
        int pageSize = normalizeLimit(limit);
        Instant now = Clock.now();
        Instant afterKey = Instant.ofEpochMilli(0);
        long afterSeq = -1;
        if (cursor != null && !cursor.isBlank()) {
            long[] c = Cursors.decode(cursor);
            afterKey = Instant.ofEpochMilli(c[0]);
            afterSeq = c[1];
        }
        List<Release> rows;
        switch (queryStatus) {
            case EXPIRING_SOON -> {
                long within = withinSeconds == null ? DEFAULT_EXPIRING_SOON_SECONDS : withinSeconds;
                if (within < 0) {
                    throw new IllegalArgumentException("withinSeconds must not be negative");
                }
                rows = releaseRepository.findExpiringSoon(now, now.plusSeconds(within),
                        afterKey, afterSeq, PageRequest.of(0, pageSize + 1));
            }
            case EXPIRED_PENDING -> rows = releaseRepository.findExpiredPendingPage(now,
                    afterKey, afterSeq, PageRequest.of(0, pageSize + 1));
            case AUTO_ROLLED_BACK -> rows = releaseRepository.findRolledBackByReason(
                    RollbackReason.AUTO_EXPIRED, afterKey, afterSeq,
                    PageRequest.of(0, pageSize + 1));
            default -> throw new IllegalArgumentException("unsupported status: " + status);
        }
        boolean hasNext = rows.size() > pageSize;
        List<Release> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasNext) {
            Release last = page.get(pageSize - 1);
            nextCursor = Cursors.encode(last.getCreatedAt().toEpochMilli(), last.getSeq());
        }
        log.info("window query decided: status={}, inputCursor={}, pageSize={}, returned={}, "
                + "hasNext={}, nextCursor={}", queryStatus, cursor, pageSize, page.size(),
                hasNext, nextCursor);
        return new CursorPage<>(page.stream().map(ReleaseService::toView).toList(), nextCursor);
    }

    /**
     * 到期清扫：把"已发布且窗口终点已到"的发布单逐张自动回滚。
     *
     * <p>每张发布单在独立事务中处理（经 {@link #rollbackAuto} 的重试包装），
     * 单张失败（如发布后被改过、前置条件不满足）不影响其它发布单，
     * 该单会继续留在"已到期未回滚"查询结果中等待下一轮。
     * 本方法幂等：重复调用、与手动回滚并发，都只会真正回滚一次。
     *
     * @return 本轮成功自动回滚的发布单数量
     */
    public int processExpiredWindows() {
        Instant now = Clock.now();
        List<Release> expired = releaseRepository.findExpiredPublished(now,
                PageRequest.of(0, SWEEP_BATCH_SIZE));
        log.info("sweep expired windows: now={}, candidates={}", now, expired.size());
        int rolledBack = 0;
        for (Release r : expired) {
            try {
                rollbackAuto(r.getRequestId());
                rolledBack++;
            } catch (BusinessException e) {
                // 确定且可解释：失败原因落日志，该单保持 PUBLISHED 并留在 EXPIRED_PENDING 列表
                log.warn("auto-rollback skipped: requestId={}, reason={}, message={}",
                        r.getRequestId(), e.getReason(), e.getMessage());
            }
        }
        log.info("sweep expired windows done: rolledBack={}/{}", rolledBack, expired.size());
        return rolledBack;
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
            // 并发状态迁移：另一个事务已推进状态，当前迁移不再合法。
            throw new BusinessException(ConflictReason.ILLEGAL_STATE_TRANSITION,
                    "release state changed concurrently: " + release.getRequestId());
        }
    }

    private Release requireRelease(String requestId) {
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
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

    /** 解析后的生效时间窗：终点可能留空（仅声明时长时，发布瞬间才结算）。 */
    record WindowSpec(Instant windowStart, Instant windowEnd, Long durationSeconds) {
    }

    /**
     * 解析并校验时间窗参数，规则确定且与调用时刻无关：
     * effectiveUntil 与 effectiveForSeconds 互斥；时长必须为正；
     * 终点（若已确定）必须晚于起点。全部缺省 -> 无时间窗。
     */
    static WindowSpec resolveWindow(Instant effectiveFrom, Instant effectiveUntil,
                                    Long effectiveForSeconds) {
        if (effectiveUntil != null && effectiveForSeconds != null) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "effectiveUntil and effectiveForSeconds are mutually exclusive");
        }
        if (effectiveForSeconds != null && effectiveForSeconds <= 0) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "effectiveForSeconds must be positive: " + effectiveForSeconds);
        }
        Instant windowEnd = effectiveUntil;
        if (windowEnd == null && effectiveFrom != null && effectiveForSeconds != null) {
            windowEnd = effectiveFrom.plusSeconds(effectiveForSeconds);
        }
        if (effectiveFrom != null && windowEnd != null && !windowEnd.isAfter(effectiveFrom)) {
            throw new BusinessException(ConflictReason.INVALID_WINDOW,
                    "window end must be after window start: start=" + effectiveFrom
                            + ", end=" + windowEnd);
        }
        return new WindowSpec(effectiveFrom, windowEnd, effectiveForSeconds);
    }

    private static boolean sameWindow(Release r, WindowSpec w) {
        return java.util.Objects.equals(r.getWindowStart(), w.windowStart())
                && java.util.Objects.equals(r.getWindowEnd(), w.windowEnd())
                && java.util.Objects.equals(r.getWindowDurationSeconds(), w.durationSeconds());
    }

    public static ReleaseView toView(Release r) {
        Long anchorRevision = null;
        for (ReleaseItem item : r.getItems()) {
            if (item.getValueVersionAtPublish() != null) {
                anchorRevision = item.getValueVersionAtPublish();
                break;
            }
        }
        return new ReleaseView(r.getSeq(), r.getRequestId(), r.getState().name(),
                r.getItems().stream().map(ReleaseItem::getEntryKey).toList(),
                r.getRemark(), anchorRevision, Math.max(r.getFormatVersion(), 1),
                r.getCreatedAt(), r.getUpdatedAt(),
                r.getWindowStart(), r.getWindowEnd(), r.getWindowDurationSeconds(),
                r.getRollbackReason() == null ? null : r.getRollbackReason().name());
    }
}
