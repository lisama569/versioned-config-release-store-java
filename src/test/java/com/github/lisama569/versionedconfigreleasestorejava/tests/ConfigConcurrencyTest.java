package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发冲突与冲突原因可区分性测试。不依赖任何外部服务，可稳定重复执行。
 */

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConfigConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigConcurrencyTest.class);

    @Autowired
    private ConfigService configService;
    @Autowired
    private ConfigRevisionRepository revisionRepository;

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    @Test
    void concurrentUpdates_onlyOneTakesEffect_othersGetVersionConflict() throws Exception {
        int threads = 8;
        String key = "feature.concurrent";
        configService.createEntry(new CreateEntryRequest(key, "v0", "d", "catA"));
        log.info("INPUT key={}, initialValue=v0, concurrentThreads={}", key, threads);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        List<ConflictReason> rejectionReasons = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    // 每个线程都基于进入前读到的版本 1 做 CAS 修改
                    configService.updateEntry(key,
                            new UpdateEntryRequest("v" + idx, null, null, 1L, null));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    synchronized (rejectionReasons) {
                        rejectionReasons.add(e.getReason());
                    }
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("DECISION success={}, rejections={} (判定依据: 唯一索引+@Version 乐观锁，仅一个事务提交成功)",
                success.get(), rejectionReasons);
        assertEquals(1, success.get(), "并发修改只能有一个生效");
        assertEquals(threads - 1, rejectionReasons.size());
        assertTrue(rejectionReasons.stream().allMatch(r -> r == ConflictReason.VERSION_CONFLICT),
                "其余全部必须是 VERSION_CONFLICT，实际: " + rejectionReasons);

        // 原子性校验：条目业务版本恰好 +1，且版本记录数与版本号严格对应（无半更新）
        EntryView current = configService.getEntry(key);
        long revisionCount = revisionRepository.findByEntrySeqOrderBySeqAsc(current.seq()).size();
        log.info("DECISION currentVersion={}, revisionRows={} (判定依据: CREATE+一次成功UPDATE=2条记录)",
                current.version(), revisionCount);
        assertEquals(2, current.version());
        assertEquals(2, revisionCount);
    }

    @Test
    void staleValueUpdate_isRejectedWithValueMismatch_distinctFromVersionConflict() {
        String key = "feature.cas";
        configService.createEntry(new CreateEntryRequest(key, "original", null, null));
        // 不带 expectedVersion，仅带 expectedValue；现值已被他人改为 changed
        configService.updateEntry(key, new UpdateEntryRequest("changed", null, null, null, null));
        log.info("INPUT key={}, expectedValue=original, actualValue=changed", key);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.updateEntry(key,
                        new UpdateEntryRequest("x", null, null, null, "original")));
        log.info("DECISION reason={} (判定依据: expectedValue 与现值不等，区别于版本号冲突)",
                ex.getReason());
        assertEquals(ConflictReason.VALUE_MISMATCH, ex.getReason());
    }

    @Test
    void duplicateCreate_isRejectedWithEntryKeyDuplicate() {
        String key = "feature.dup";
        configService.createEntry(new CreateEntryRequest(key, "a", null, null));
        log.info("INPUT duplicate create key={}", key);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.createEntry(new CreateEntryRequest(key, "b", null, null)));
        log.info("DECISION reason={} (判定依据: 唯一键 uk_entry_key)", ex.getReason());
        assertEquals(ConflictReason.ENTRY_KEY_DUPLICATE, ex.getReason());
    }

    @Test
    void updateMissingEntry_isRejectedWithEntryNotFound() {
        log.info("INPUT update missing key=ghost");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.updateEntry("ghost",
                        new UpdateEntryRequest("v", null, null, null, null)));
        log.info("DECISION reason={}", ex.getReason());
        assertEquals(ConflictReason.ENTRY_NOT_FOUND, ex.getReason());
    }
}
