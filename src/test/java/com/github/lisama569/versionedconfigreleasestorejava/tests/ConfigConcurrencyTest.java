package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发冲突测试：同一配置被并发修改时只能有一个生效，其余以 VERSION_CONFLICT 被拒绝；
 * 并发创建同一 key 只有一个成功；失败不得产生多余版本（无部分写入）。
 * 单测日志打印输入（线程数/期望版本）与判定依据（成功/冲突计数、最终版本号）。
 */
class ConfigConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigConcurrencyTest.class);

    @Test
    void concurrentUpdatesOnSameBaseVersion_exactlyOneWinsOthersConflict() throws Exception {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("feature.toggle", "off");

        int threads = 16;
        log.info("输入: key=feature.toggle, 并发线程={}, 所有线程均基于版本 v1 提交修改", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                Callable<Void> task = () -> {
                    start.await();
                    try {
                        system.configs().update("feature.toggle", 1L, "value-" + idx);
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                        conflict.incrementAndGet();
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long finalVersion = system.configs().get("feature.toggle").orElseThrow().getCurrentVersion();
        long versionCount = system.queries().listVersionsOf("feature.toggle", null, 200)
                .getItems().size();

        log.info("判定依据: 成功={}, 冲突={}, 最终版本=v{}, 历史版本条数={}",
                success.get(), conflict.get(), finalVersion, versionCount);

        assertThat(success.get()).as("只有一个并发修改可以生效").isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(finalVersion).as("唯一生效的修改把版本推进到 v2").isEqualTo(2L);
        assertThat(versionCount).as("失败的修改不得留下版本记录（无部分写入）").isEqualTo(2L);
    }

    @Test
    void concurrentCreatesOfSameKey_exactlyOneWins() throws Exception {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        int threads = 12;
        log.info("输入: 并发生成同一新 key=shared, 线程数={}", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Void> task = () -> {
                    start.await();
                    try {
                        system.configs().create("shared", "v");
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                        conflict.incrementAndGet();
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("判定依据: 创建成功={}, 创建冲突={}, 条目当前版本=v{}",
                success.get(), conflict.get(),
                system.configs().get("shared").orElseThrow().getCurrentVersion());
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
    }

    @Test
    void sequentialRetryAfterConflict_succeedsAndAdvancesVersion() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("retry.key", "a");
        log.info("输入: 先用过期版本 v1 修改（预期冲突），再以最新版本 v2 重试（预期成功）");

        system.configs().update("retry.key", 1L, "b"); // v1 -> v2

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> system.configs().update("retry.key", 1L, "stale"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        log.info("判定依据: 过期提交被拒绝 errorCode={}, message={}",
                ex.getErrorCode(), ex.getMessage());

        var retry = system.configs().update("retry.key", 2L, "c");
        log.info("判定依据: 基于最新版本重试成功, 新版本=v{}", retry.version());
        assertThat(retry.version()).isEqualTo(3L);
        assertThat(system.configs().get("retry.key").orElseThrow().getCurrentVersion())
                .isEqualTo(3L);
    }
}
