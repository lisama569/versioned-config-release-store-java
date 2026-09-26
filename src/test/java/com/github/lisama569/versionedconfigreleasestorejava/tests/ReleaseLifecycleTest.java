package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ReleaseRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布状态机、幂等重放与回滚语义测试。
 */

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseLifecycleTest.class);

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ReleaseRepository releaseRepository;

    @Test
    void happyFlow_draftToPendingToPublishedToRolledBack() {
        configService.createEntry(new CreateEntryRequest("k.flow", "draft-value", null, null));
        ReleaseView r = releaseService.create(
                new CreateReleaseRequest("req-flow", List.of("k.flow"), "flow test"));
        assertEquals("DRAFT", r.state());

        r = releaseService.submit("req-flow");
        assertEquals("PENDING", r.state());
        r = releaseService.publish("req-flow");
        assertEquals("PUBLISHED", r.state());

        r = releaseService.rollback("req-flow");
        assertEquals("ROLLED_BACK", r.state());
        EntryView entry = configService.getEntry("k.flow");
        log.info("DECISION afterRollback value={} (判定依据: 恢复为发布单创建时冻结的 draft-value)",
                entry.value());
        assertEquals("draft-value", entry.value());
        assertEquals(2, entry.version(), "CREATE 版本为 1，回滚追加一次 RESTORE 后为 2");
    }

    @Test
    void illegalTransitions_areRejectedWithDistinctReason() {
        configService.createEntry(new CreateEntryRequest("k.illegal", "v", null, null));
        releaseService.create(new CreateReleaseRequest("req-illegal", List.of("k.illegal"), null));

        // DRAFT 直接 publish / rollback 都非法
        assertIllegal("req-illegal", () -> releaseService.publish("req-illegal"));
        assertIllegal("req-illegal", () -> releaseService.rollback("req-illegal"));

        releaseService.submit("req-illegal"); // PENDING
        // PENDING 再 submit 重放为幂等 no-op；直接 rollback 非法
        assertEquals("PENDING", releaseService.submit("req-illegal").state());
        assertIllegal("req-illegal", () -> releaseService.rollback("req-illegal"));

        releaseService.publish("req-illegal"); // PUBLISHED
        // PUBLISHED 再 submit / publish 迁移非法（publish 重放本身幂等等价于 no-op）
        assertIllegal("req-illegal", () -> releaseService.submit("req-illegal"));

        releaseService.rollback("req-illegal"); // ROLLED_BACK
        // 终态上的一切迁移均非法（rollback 重放幂等，submit/publish 非法）
        assertIllegal("req-illegal", () -> releaseService.submit("req-illegal"));
        assertIllegal("req-illegal", () -> releaseService.publish("req-illegal"));
    }

    private void assertIllegal(String ignored, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        log.info("DECISION illegal transition reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(ConflictReason.ILLEGAL_STATE_TRANSITION, ex.getReason());
    }

    @Test
    void duplicateSubmitAndPublish_andReplay_returnSameState_withoutDoubleEffect() {
        configService.createEntry(new CreateEntryRequest("k.idem", "v", null, null));
        releaseService.create(new CreateReleaseRequest("req-idem", List.of("k.idem"), null));

        ReleaseView first = releaseService.submit("req-idem");
        ReleaseView second = releaseService.submit("req-idem"); // 重放
        log.info("DECISION submit replay firstState={}, secondState={}, sameSeq={}",
                first.state(), second.state(), first.seq() == second.seq());
        assertEquals(first.state(), second.state());
        assertEquals(first.seq(), second.seq());

        ReleaseView p1 = releaseService.publish("req-idem");
        ReleaseView p2 = releaseService.publish("req-idem"); // 重放不得重复生效
        assertEquals("PUBLISHED", p1.state());
        assertEquals("PUBLISHED", p2.state());
        log.info("DECISION publish replay state={}, seq={} (无第二次发布副作用)", p2.state(), p2.seq());

        ReleaseView rb1 = releaseService.rollback("req-idem");
        ReleaseView rb2 = releaseService.rollback("req-idem");
        assertEquals("ROLLED_BACK", rb1.state());
        assertEquals("ROLLED_BACK", rb2.state());
        // 重放回滚没有再次恢复：条目版本只被回滚推进一次
        EntryView entry = configService.getEntry("k.idem");
        log.info("DECISION rollback replay finalVersion={} (判定依据: CREATE=1, RESTORE 仅一次 => 2)",
                entry.version());
        assertEquals(2, entry.version());
    }

    @Test
    void createWithSameRequestIdButDifferentPayload_isRejected() {
        configService.createEntry(new CreateEntryRequest("k.a", "v", null, null));
        configService.createEntry(new CreateEntryRequest("k.b", "v", null, null));
        releaseService.create(new CreateReleaseRequest("req-conflict", List.of("k.a"), null));
        log.info("INPUT same requestId req-conflict with different keys [k.b]");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> releaseService.create(
                        new CreateReleaseRequest("req-conflict", List.of("k.b"), null)));
        log.info("DECISION reason={}", ex.getReason());
        assertEquals(ConflictReason.IDEMPOTENCY_KEY_MISMATCH, ex.getReason());
    }

    @Test
    void rollbackAfterPostPublishEdit_isRejectedByPrecondition() {
        String key = "k.postedit";
        configService.createEntry(new CreateEntryRequest(key, "at-create", null, null));
        releaseService.create(new CreateReleaseRequest("req-postedit", List.of(key), null));
        releaseService.submit("req-postedit");
        releaseService.publish("req-postedit");

        // 发布之后又有人改了配置
        configService.updateEntry(key, new UpdateEntryRequest("after-publish-edit", null, null, null, null));
        log.info("INPUT rollback requestId=req-postedit, entry changed after publish");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> releaseService.rollback("req-postedit"));
        log.info("DECISION reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(ConflictReason.ROLLBACK_PRECONDITION_FAILED, ex.getReason());
        // 被拒绝后值与状态都保持不变（无半更新）
        assertEquals("after-publish-edit", configService.getEntry(key).value());
        assertEquals("PUBLISHED", releaseService.get("req-postedit").state());
    }

    @Test
    void concurrentCreateSameRequestId_onlyOneRow_allCallsSucceedViaIdempotency() throws Exception {
        configService.createEntry(new CreateEntryRequest("k.concurrent.rel", "v", null, null));
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ReleaseView> returned = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                // 相同请求并发提交：一个真正创建，其余必须作为幂等重放返回同一发布单，
                // 不能抛错、更不能产生第二行。
                returned.add(releaseService.create(new CreateReleaseRequest(
                        "req-concurrent-same", List.of("k.concurrent.rel"), null)));
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("DECISION concurrent calls returned={}, distinctSeqs={}",
                returned.size(), returned.stream().map(ReleaseView::seq).distinct().count());
        assertEquals(threads, returned.size(), "全部并发调用都应成功返回（幂等重放不报错）");
        assertEquals(1, returned.stream().map(ReleaseView::seq).distinct().count(),
                "数据库中只能有一个发布单，所有响应必须指向同一 seq");
        assertEquals("DRAFT", returned.get(0).state());
        // 数据库层终态校验：同一 requestId 物理上只有一行
        assertEquals(1L, releaseRepository.countByRequestId("req-concurrent-same"));
    }

    @Test
    void createWithEmptyEntries_isRejectedAndMissingEntryIsReported() {        BusinessException empty = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-empty", List.of(), null)));
        assertEquals(ConflictReason.EMPTY_RELEASE, empty.getReason());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> releaseService.create(
                        new CreateReleaseRequest("req-missing", List.of("does.not.exist"), null)));
        log.info("DECISION missing-entry reason={}", missing.getReason());
        assertEquals(ConflictReason.ENTRY_NOT_FOUND, missing.getReason());
    }
}
