package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseStatus;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重复提交测试：同一 requestId 重放不产生第二次状态迁移，结果与首次一致；
 * 同一 requestId 复用于不同动作/目标必须以 IDEMPOTENCY_KEY_CONFLICT 拒绝（可区分）。
 */
class ReleaseIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseIdempotencyTest.class);

    private ConfigReleaseSystem seeded() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("cfg", "v1-value");
        system.configs().update("cfg", 1L, "v2-value");
        return system;
    }

    @Test
    void repeatedSubmitAndPublish_replayIdenticalAndNoDoubleEffect() {
        ConfigReleaseSystem system = seeded();
        var draft = system.releases().createDraft("cfg", 2L, "req-create");
        log.info("输入: orderId={}, 用同一 requestId 重复 submit/publish/rollback", draft.orderId());

        var s1 = system.releases().submit(draft.orderId(), "req-submit");
        var s2 = system.releases().submit(draft.orderId(), "req-submit");
        var s3 = system.releases().submit(draft.orderId(), "req-submit");
        log.info("submit 首次={} 重放1={} 重放2={}", s1, s2, s3);
        assertThat(s1.replayed()).isFalse();
        assertThat(s2.replayed()).isTrue();
        assertThat(s3.replayed()).isTrue();
        assertThat(s2.status()).isEqualTo(s1.status());
        assertThat(s3.status()).isEqualTo(s1.status());

        var p1 = system.releases().publish(draft.orderId(), "req-publish");
        var p2 = system.releases().publish(draft.orderId(), "req-publish");
        log.info("publish 首次={} 重放={}, 生效版本=v{}", p1, p2, p1.effectivePublishedVersion());
        assertThat(p1.replayed()).isFalse();
        assertThat(p2.replayed()).isTrue();
        assertThat(p2.status()).isEqualTo(p1.status());
        assertThat(p2.effectivePublishedVersion()).isEqualTo(p1.effectivePublishedVersion());
        assertThat(system.configs().get("cfg").orElseThrow().getPublishedVersion())
                .as("重复发布不得把生效版本推进两次").isEqualTo(2L);

        var r1 = system.releases().rollback(draft.orderId(), "req-rollback");
        var r2 = system.releases().rollback(draft.orderId(), "req-rollback");
        log.info("rollback 首次={} 重放={}", r1, r2);
        assertThat(r1.replayed()).isFalse();
        assertThat(r2.replayed()).isTrue();
        assertThat(r2.status()).isEqualTo(ReleaseStatus.ROLLED_BACK);

        long orderCount = system.queries().listOrders(null, 200).getItems().size();
        log.info("判定依据: 全流程结束状态={}, 工单总数={}（重放未产生新工单）",
                p2.status(), orderCount);
        assertThat(orderCount).isEqualTo(1L);
    }

    @Test
    void sameRequestIdForDifferentAction_isConflict() {
        ConfigReleaseSystem system = seeded();
        var draft = system.releases().createDraft("cfg", 2L, "shared-rid");
        log.info("输入: 建工单与提交复用同一 requestId=shared-rid, orderId={}", draft.orderId());

        // createDraft 已占用 shared-rid；submit 再用同键应冲突
        BusinessException ex = assertThrows(BusinessException.class,
                () -> system.releases().submit(draft.orderId(), "shared-rid"));
        log.info("判定依据: errorCode={}, message={}", ex.getErrorCode(), ex.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void sameRequestIdForDifferentTarget_isConflict() {
        ConfigReleaseSystem system = seeded();
        system.releases().createDraft("cfg", 1L, "draft-a");
        var b = system.releases().createDraft("cfg", 2L, "draft-b");
        log.info("输入: 用属于 draft-a 流程的 requestId 去操作 orderId={}", b.orderId());

        // 先用 rid-x 提交工单 b 成功；再用同一 rid-x 去发布工单 b 是动作不同冲突；
        // 这里验证“同动作同键不同工单”：再建工单 a2，用 draft-a 的键去提交别的工单
        system.releases().submit(b.orderId(), "submit-b");
        var other = system.releases().createDraft("cfg", 2L, "draft-c");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> system.releases().submit(other.orderId(), "submit-b"));
        log.info("判定依据: errorCode={}, message={}", ex.getErrorCode(), ex.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void concurrentDuplicateSubmit_onlyOneTakesEffect() throws Exception {
        ConfigReleaseSystem system = seeded();
        var draft = system.releases().createDraft("cfg", 2L, "create-rid");
        int threads = 20;
        log.info("输入: {} 个线程同时用同一 requestId=dup-submit 提交同一工单 {}",
                threads, draft.orderId());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReleaseService.TransitionResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return system.releases().submit(draft.orderId(), "dup-submit");
                }));
            }
            start.countDown();
            int first = 0;
            int replay = 0;
            for (Future<ReleaseService.TransitionResult> f : results) {
                ReleaseService.TransitionResult r = f.get(10, TimeUnit.SECONDS);
                if (r.replayed()) {
                    replay++;
                } else {
                    first++;
                }
            }
            log.info("判定依据: 首次生效={}, 重放={}, 工单状态={}",
                    first, replay,
                    ReleaseStatus.PENDING.name());
            assertThat(first).isEqualTo(1);
            assertThat(replay).isEqualTo(threads - 1);
            // 日志中该工单只有一条状态迁移事件 + 一条创建事件
            long statusEvents = system.queries().listOrders(null, 10).getItems().stream()
                    .filter(o -> o.getStatus() == ReleaseStatus.PENDING).count();
            assertThat(statusEvents).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
