package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ChangeType;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import com.github.lisama569.versionedconfigreleasestorejava.domain.Release;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseItem;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseState;
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

    /** 同 requestId 创建临界区的进程内串行锁（数据库唯一索引为跨进程兜底）。 */
    private final com.github.lisama569.versionedconfigreleasestorejava.support.KeyedLocks
            createLocks = new com.github.lisama569.versionedconfigreleasestorejava.support.KeyedLocks();

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
        log.info("createRelease input: requestId={}, keys={}, remark={}",
                request.requestId(), normalizedKeys, request.remark());

        // 同一 requestId 的"查重 -> 插入"临界区串行化：第一个创建者完成事务后，
        // 后来者要么命中既有单走幂等重放，要么被唯一索引兜底，绝不双生效。
        return createLocks.withLock(request.requestId(),
                () -> creationTx.createInTransaction(request, normalizedKeys));
    }

    /** 事务化的真正创建逻辑，仅由 {@link ReleaseCreationTx} 在持锁状态下调用。 */
    ReleaseView doCreate(CreateReleaseRequest request, List<String> normalizedKeys) {
        Optional<Release> existing = releaseRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Release r = existing.get();
            List<String> existingKeys = r.getItems().stream().map(ReleaseItem::getEntryKey).toList();
            if (!existingKeys.equals(normalizedKeys)
                    || !safeEquals(r.getRemark(), request.remark())) {
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
        return transition(release, ReleaseState.PUBLISHED, now);
    }

    /**
     * PUBLISHED -> ROLLED_BACK。
     * 回滚语义：将条目值恢复为"发布单创建时冻结的值"，并为每个条目追加 RESTORE 版本记录。
     * 前置条件：发布之后条目没有被再修改（当前业务版本 == 发布时版本），否则拒绝。
     */
    @Transactional
    public ReleaseView rollback(String requestId) {
        log.info("rollback input: requestId={}", requestId);
        Release release = requireRelease(requestId);
        if (release.getState() == ReleaseState.ROLLED_BACK) {
            log.info("rollback replayed/no-op: requestId={}, state=ROLLED_BACK", requestId);
            return toView(release);
        }
        if (release.getState() != ReleaseState.PUBLISHED) {
            return transition(release, ReleaseState.ROLLED_BACK);
        }

        Instant now = Clock.now();
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
                    oldValue, restoreValue, "system-rollback:" + requestId, now));
        }
        return transition(release, ReleaseState.ROLLED_BACK, now);
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
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
