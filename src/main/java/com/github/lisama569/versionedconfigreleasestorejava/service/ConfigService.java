package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.RevisionView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ChangeType;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigEntryRepository;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import com.github.lisama569.versionedconfigreleasestorejava.support.Cursors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final int MAX_LIMIT = 200;

    private final ConfigEntryRepository entryRepository;
    private final ConfigRevisionRepository revisionRepository;

    public ConfigService(ConfigEntryRepository entryRepository,
                         ConfigRevisionRepository revisionRepository) {
        this.entryRepository = entryRepository;
        this.revisionRepository = revisionRepository;
    }

    @Transactional
    public EntryView createEntry(CreateEntryRequest request) {
        validateKey(request.key());
        if (request.value() == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String description = normalizeNullable(request.description());
        String category = normalizeCategory(request.category());
        log.info("createEntry input: key={}, valueLength={}, category={}",
                request.key(), request.value().length(), category);

        // 先查一次给出可读错误；并发下仍由唯一索引兜底。
        if (entryRepository.existsByKey(request.key())) {
            throw new BusinessException(ConflictReason.ENTRY_KEY_DUPLICATE,
                    "config key already exists: " + request.key());
        }
        Instant now = Clock.now();
        ConfigEntry entry;
        try {
            entry = entryRepository.saveAndFlush(new ConfigEntry(
                    request.key(), request.value(), description, category, now));
            revisionRepository.save(new ConfigRevision(entry.getSeq(), entry.getKey(),
                    ChangeType.CREATE, 0L, 1L, null, request.value(), "system", now));
            // 同一事务提交：条目与首版本记录要么都生效，要么都不生效。
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ConflictReason.ENTRY_KEY_DUPLICATE,
                    "config key already exists (unique index): " + request.key());
        }
        log.info("createEntry accepted: key={}, seq={}, valueVersion=1", entry.getKey(), entry.getSeq());
        return toView(entry);
    }

    @Transactional
    public EntryView updateEntry(String key, UpdateEntryRequest request) {
        validateKey(key);
        if (request == null || request.value() == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        ConfigEntry entry = entryRepository.findByKey(key)
                .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                        "config key not found: " + key));

        log.info("updateEntry input: key={}, currentVersion={}, expectedVersion={}, expectedValue={}",
                key, entry.getValueVersion(), request.expectedVersion(),
                request.expectedValue() == null ? "<absent>" : "<provided>");

        // 并发判定分两层，错误原因可区分：
        // 1) 业务版本号（CAS）：调用方基于过期版本修改
        if (request.expectedVersion() != null
                && request.expectedVersion() != entry.getValueVersion()) {
            throw new BusinessException(ConflictReason.VERSION_CONFLICT,
                    "expected version " + request.expectedVersion()
                            + " but current version is " + entry.getValueVersion());
        }
        // 2) 值条件：调用方以为没人动过这个值
        if (request.expectedValue() != null
                && !request.expectedValue().equals(entry.getValue())) {
            throw new BusinessException(ConflictReason.VALUE_MISMATCH,
                    "expected current value to match expectedValue, but it has changed");
        }

        long versionFrom = entry.getValueVersion();
        String oldValue = entry.getValue();
        Instant now = Clock.now();
        String newDescription = request.description() == null
                ? normalizeNullable(entry.getDescription())
                : request.description();
        String newCategory = request.category() == null
                ? normalizeCategory(entry.getCategory())
                : normalizeCategory(request.category());
        entry.applyChange(request.value(), newDescription, newCategory, now);
        try {
            entryRepository.saveAndFlush(entry); // @Version 在此检测并发写
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ConflictReason.VERSION_CONFLICT,
                    "entry was modified concurrently, please retry with latest version: " + key);
        }
        revisionRepository.save(new ConfigRevision(entry.getSeq(), key, ChangeType.UPDATE,
                versionFrom, entry.getValueVersion(), oldValue, request.value(), "system", now));
        log.info("updateEntry accepted: key={}, version {} -> {}",
                key, versionFrom, entry.getValueVersion());
        return toView(entry);
    }

    @Transactional(readOnly = true)
    public EntryView getEntry(String key) {
        validateKey(key);
        return entryRepository.findByKey(key).map(ConfigService::toView)
                .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                        "config key not found: " + key));
    }

    @Transactional(readOnly = true)
    public CursorPage<EntryView> listEntries(String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        List<ConfigEntry> rows;
        String logCursor;
        if (cursor == null || cursor.isBlank()) {
            rows = entryRepository.findFirstPage(PageRequest.of(0, pageSize + 1));
            logCursor = "<first>";
        } else {
            long[] c = Cursors.decode(cursor);
            rows = entryRepository.findPageAfter(Instant.ofEpochMilli(c[0]), c[1],
                    PageRequest.of(0, pageSize + 1));
            logCursor = cursor;
        }
        boolean hasNext = rows.size() > pageSize;
        List<ConfigEntry> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasNext) {
            ConfigEntry last = page.get(pageSize - 1);
            nextCursor = Cursors.encode(last.getCreatedAt().toEpochMilli(), last.getSeq());
        }
        log.debug("entry page decided: inputCursor={}, pageSize={}, returned={}, hasNext={}, nextCursor={}",
                logCursor, pageSize, page.size(), hasNext, nextCursor);
        return new CursorPage<>(page.stream().map(ConfigService::toView).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public CursorPage<RevisionView> listRevisions(String key, String cursor, int limit) {
        validateKey(key);
        ConfigEntry entry = entryRepository.findByKey(key)
                .orElseThrow(() -> new BusinessException(ConflictReason.ENTRY_NOT_FOUND,
                        "config key not found: " + key));
        int pageSize = normalizeLimit(limit);
        List<ConfigRevision> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = revisionRepository.findEntryFirstPage(entry.getSeq(), PageRequest.of(0, pageSize + 1));
        } else {
            long afterSeq = parseSeqCursor(cursor);
            rows = revisionRepository.findEntryPageAfter(entry.getSeq(), afterSeq,
                    PageRequest.of(0, pageSize + 1));
        }
        boolean hasNext = rows.size() > pageSize;
        List<ConfigRevision> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext
                ? Long.toString(page.get(pageSize - 1).getSeq())
                : null;
        log.debug("revision page decided: key={}, inputCursor={}, returned={}, nextCursor={}",
                key, cursor, page.size(), nextCursor);
        return new CursorPage<>(page.stream().map(ConfigService::toRevisionView).toList(), nextCursor);
    }

    /** 版本线游标是单个 seq（该条目内的键集位置）。 */
    private static long parseSeqCursor(String cursor) {
        try {
            long seq = Long.parseLong(cursor.trim());
            if (seq < 0) {
                throw new IllegalArgumentException("malformed cursor: " + cursor);
            }
            return seq;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed cursor: " + cursor, e);
        }
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    private static String normalizeNullable(String value) {
        return value == null ? "" : value;
    }

    /**
     * 历史数据兼容归一化：category 为后补字段，旧行为 null 或空时稳定映射为 DEFAULT；
     * description 为 null 稳定映射为空串。归一化结果确定、与读取次数无关。
     */
    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return ConfigEntry.DEFAULT_CATEGORY;
        }
        return category;
    }

    public static EntryView toView(ConfigEntry e) {
        return new EntryView(e.getSeq(), e.getKey(), e.getValue(),
                normalizeNullable(e.getDescription()),
                normalizeCategory(e.getCategory()),
                EntryView.EntryStatusView.valueOf(e.getStatus().name()),
                e.getValueVersion(),
                Math.max(e.getFormatVersion(), 1),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    public static RevisionView toRevisionView(ConfigRevision r) {
        return new RevisionView(r.getSeq(), r.getEntrySeq(), r.getChangeType().name(),
                r.getVersionFrom(), r.getVersionTo(),
                r.getOldValue(), r.getNewValue(), r.getOperator(), r.getCreatedAt());
    }
}
