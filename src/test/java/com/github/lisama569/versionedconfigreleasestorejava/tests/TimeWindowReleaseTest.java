package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.SweepResult;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生效时间窗与自动回滚测试：
 * 到期自动回滚、手动与自动相撞、重复/并发触发只生效一次且结论一致、
 * 未到期手回、过期后重复触发、改期/取消边界、生效时刻门控、TTL、非法窗口。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TimeWindowReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(TimeWindowReleaseTest.class);

    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ConfigRevisionRepository revisionRepository;

    @BeforeEach
    void fixClock() {
        Clock.fixedAt(T0);
    }

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    private String newEntry(String key, String value) {
        configService.createEntry(new CreateEntryRequest(key, value, null, null));
        return key;
    }

    private void publish(String reqId) {
        releaseService.submit(reqId);
        releaseService.publish(reqId);
    }

    // ---------------------------------------------------------------
    // 1) 到期自动回滚：值恢复 + AUTO 记录可追溯
    // ---------------------------------------------------------------

    @Test
    void absoluteWindow_expiresThenSweep_autoRollsBackAndRestoresValueOnce() {
        String key = newEntry("tw.auto", "at-create-v");
        String req = "req-tw-auto";
        Instant expire = T0.plusSeconds(3600);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expire, null)));
        publish(req);
        // 发布后值被发布动作带出（本例创建时冻结即 at-create-v，回滚恢复同一基线）
        Clock.fixedAt(expire.plusSeconds(10));
        log.info("INPUT sweep at now={}, expireAt={} (判定依据: now > expireAt => 到期)",
                Clock.now(), expire);

        SweepResult first = releaseService.sweepExpiredReleases();
        assertEquals(1, first.scanned());
        assertEquals(1, first.succeeded());
        assertEquals(List.of(req), first.targets());

        ReleaseView view = releaseService.get(req);
        assertEquals("ROLLED_BACK", view.state());
        assertEquals("AUTO", view.rollbackTrigger(), "自动回滚来源必须可解释为 AUTO");
        assertEquals("at-create-v", configService.getEntry(key).value());
        log.info("DECISION auto rolled back: state={}, trigger={}, value={}",
                view.state(), view.rollbackTrigger(), configService.getEntry(key).value());

        // 同一批过期单只回滚一次：再扫一次，候选为空（已不在 PUBLISHED）
        SweepResult second = releaseService.sweepExpiredReleases();
        assertEquals(0, second.scanned(), "已自动回滚的发布单不得再次进入到期候选");
        assertEquals(2L, configService.getEntry(key).version(), "CREATE=1, RESTORE 仅一次 => 2");

        long restoreRecords = revisionRepository.findAll().stream()
                .filter(r -> key.equals(r.getEntryKey()))
                .filter(r -> r.getChangeType().name().equals("RESTORE"))
                .filter(r -> r.getOperator() != null && r.getOperator().startsWith("system-auto-rollback"))
                .count();
        assertEquals(1, restoreRecords, "必须只留下一条 AUTO 的 RESTORE 可追溯记录");
    }

    // ---------------------------------------------------------------
    // 2) 手动与自动相撞：手动先回滚后，扫描与重复触发都收敛为同一结论
    // ---------------------------------------------------------------

    @Test
    void manualRollbackBeforeDue_sweepAndReplayConvergeToSameTerminalView() {
        String key = newEntry("tw.manualfirst", "base-v");
        String req = "req-tw-manualfirst";
        Instant expire = T0.plusSeconds(3600);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expire, null)));
        publish(req);

        // 窗口未到，人工先回滚
        ReleaseView manual = releaseService.rollback(req);
        assertEquals("ROLLED_BACK", manual.state());
        assertEquals("MANUAL", manual.rollbackTrigger());
        log.info("DECISION early manual rollback: trigger={}", manual.rollbackTrigger());

        Clock.fixedAt(expire.plusSeconds(5));
        SweepResult sweep = releaseService.sweepExpiredReleases();
        assertEquals(0, sweep.scanned(), "已人工回滚的单子不在自动到期候选内");

        // 过期后又被重复触发（手动/自动）：都必须返回同一个终态视图，不再二次生效
        ReleaseView againManual = releaseService.rollback(req);
        ReleaseView againAuto = releaseService.autoRollback(req);
        assertEquals(manual.seq(), againManual.seq());
        assertEquals(manual.seq(), againAuto.seq());
        assertEquals("ROLLED_BACK", againManual.state());
        assertEquals("ROLLED_BACK", againAuto.state());
        assertEquals("MANUAL", againManual.rollbackTrigger(), "触发来源保持首个生效者 MANUAL");
        assertEquals("MANUAL", againAuto.rollbackTrigger(), "自动重放也必须看到同一来源 MANUAL");
        assertEquals(2L, configService.getEntry(key).version());
        log.info("DECISION replay after expiry converge: both state=ROLLED_BACK trigger=MANUAL version=2");
    }

    // ---------------------------------------------------------------
    // 3) 已到期后并发触发：只有一次真实生效，全部得到同一状态/来源
    // ---------------------------------------------------------------

    @Test
    void concurrentTriggersAfterExpiry_onlyOneEffect_allReturnSameConclusion() throws Exception {
        String key = newEntry("tw.concurrent", "base-v");
        String req = "req-tw-concurrent";
        Instant expire = T0.plusSeconds(600);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expire, null)));
        publish(req);
        Clock.fixedAt(expire.plusSeconds(1));

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ReleaseView> views = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean asAuto = (i % 2 == 0);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    views.add(asAuto ? releaseService.autoRollback(req)
                            : releaseService.rollback(req));
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("concurrent trigger got error", e);
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

        log.info("DECISION concurrent triggers: returned={}, errors={}, distinctStates={}, distinctTriggers={}",
                views.size(), errors.get(),
                views.stream().map(ReleaseView::state).distinct().count(),
                views.stream().map(ReleaseView::rollbackTrigger).distinct().count());
        assertEquals(0, errors.get(), "任何并发触发都不能得到含糊/冲突的错误");
        assertEquals(threads, views.size());
        assertEquals(1, views.stream().map(ReleaseView::state).distinct().count());
        assertEquals(1, views.stream().map(ReleaseView::rollbackTrigger).distinct().count());
        assertEquals("ROLLED_BACK", views.get(0).state());
        // 首个生效者确定来源；全部调用看到同一个来源
        String winner = views.get(0).rollbackTrigger();
        assertTrue(winner.equals("AUTO") || winner.equals("MANUAL"));
        assertTrue(views.stream().allMatch(v -> winner.equals(v.rollbackTrigger())));
        assertEquals(2L, configService.getEntry(key).version(), "RESTORE 恰好一次");
    }

    // ---------------------------------------------------------------
    // 4) 到点之前改期；到点之后改期/取消被拒；取消后永不自动回滚
    // ---------------------------------------------------------------

    @Test
    void rescheduleBeforeDue_changesDeadline_andCancelRemovesAutoRollback() {
        String key = newEntry("tw.resched", "base-v");
        String req = "req-tw-resched";
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, T0.plusSeconds(600), null)));
        publish(req);

        // 未到期改期到更远的未来：原到期点不应触发
        ReleaseView moved = releaseService.rescheduleWindow(req,
                new WindowRequest(null, T0.plusSeconds(7200), null));
        assertEquals(T0.plusSeconds(7200), moved.actualExpireAt());

        Clock.fixedAt(T0.plusSeconds(601));
        SweepResult none = releaseService.sweepExpiredReleases();
        assertEquals(0, none.scanned(), "改期后旧到期点失效，不应被扫描回滚");
        assertEquals("PUBLISHED", releaseService.get(req).state());

        // 取消窗口：此后永不参与自动回滚
        ReleaseView canceled = releaseService.cancelWindow(req);
        assertNull(canceled.actualExpireAt());
        Clock.fixedAt(T0.plusSeconds(99999));
        SweepResult stillNone = releaseService.sweepExpiredReleases();
        assertEquals(0, stillNone.scanned());
        assertEquals("PUBLISHED", releaseService.get(req).state(), "无窗口发布单永远不被自动回滚");
        log.info("DECISION canceled window: state stays PUBLISHED even far beyond old deadline");
    }

    @Test
    void rescheduleAndCancelAfterDue_areRejectedWithWindowExpired() {
        String key = newEntry("tw.expiredlock", "base-v");
        String req = "req-tw-expiredlock";
        Instant expire = T0.plusSeconds(300);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expire, null)));
        publish(req);
        Clock.fixedAt(expire.plusSeconds(1));

        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> releaseService.rescheduleWindow(req,
                        new WindowRequest(null, expire.plusSeconds(3600), null)));
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> releaseService.cancelWindow(req));
        log.info("DECISION after-due reschedule reason={}, cancel reason={}",
                ex1.getReason(), ex2.getReason());
        assertSame(ConflictReason.WINDOW_EXPIRED, ex1.getReason());
        assertSame(ConflictReason.WINDOW_EXPIRED, ex2.getReason());
        assertEquals("PUBLISHED", releaseService.get(req).state(), "被拒后状态不变");
    }

    // ---------------------------------------------------------------
    // 5) 生效时刻门控 + 到点自动发布
    // ---------------------------------------------------------------

    @Test
    void effectiveAt_gatesManualPublish_andSweepAutoPublishesAtDue() {
        String key = newEntry("tw.eff", "base-v");
        String req = "req-tw-eff";
        Instant eff = T0.plusSeconds(120);
        Instant expire = T0.plusSeconds(1200);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(eff, expire, null)));
        releaseService.submit(req);

        BusinessException early = assertThrows(BusinessException.class,
                () -> releaseService.publish(req));
        log.info("DECISION publish before effectiveAt reason={}", early.getReason());
        assertSame(ConflictReason.WINDOW_NOT_EFFECTIVE, early.getReason());

        Clock.fixedAt(eff);
        SweepResult published = releaseService.sweepEffectiveReleases();
        assertEquals(1, published.succeeded());
        assertEquals("PUBLISHED", releaseService.get(req).state());

        // 窗口最终仍能自动到期回滚
        Clock.fixedAt(expire.plusSeconds(1));
        assertEquals(1, releaseService.sweepExpiredReleases().succeeded());
        assertEquals("ROLLED_BACK", releaseService.get(req).state());
        assertEquals("AUTO", releaseService.get(req).rollbackTrigger());
    }

    // ---------------------------------------------------------------
    // 6) TTL 窗口：发布时刻 + ttl 固化为绝对到期点
    // ---------------------------------------------------------------

    @Test
    void ttlWindow_deadlineMaterializedAtPublish_andAutoRollsBack() {
        String key = newEntry("tw.ttl", "base-v");
        String req = "req-tw-ttl";
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, null, 600L)));
        publish(req);
        ReleaseView v = releaseService.get(req);
        assertEquals(T0.plusSeconds(600), v.actualExpireAt(),
                "TTL 到期点必须在发布时固化为 publishedAt + ttl");
        log.info("DECISION ttl materialized: publishedAt={}, ttl=600 => expireAt={}",
                T0, v.actualExpireAt());

        Clock.fixedAt(T0.plusSeconds(599));
        assertEquals(0, releaseService.sweepExpiredReleases().scanned());
        Clock.fixedAt(T0.plusSeconds(600));
        SweepResult r = releaseService.sweepExpiredReleases();
        assertEquals(1, r.succeeded());
        assertEquals("ROLLED_BACK", releaseService.get(req).state());
    }

    // ---------------------------------------------------------------
    // 7) 非法窗口参数：不同失败原因可区分
    // ---------------------------------------------------------------

    @Test
    void invalidWindows_areRejectedWithDistinctReasons() {
        newEntry("tw.invalid", "v");
        // expireAt 与 ttl 同时给
        BusinessException both = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-inv-both",
                        List.of("tw.invalid"), null,
                        new WindowRequest(null, T0.plusSeconds(10), 10L))));
        // 到期时刻在过去
        BusinessException past = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-inv-past",
                        List.of("tw.invalid"), null,
                        new WindowRequest(null, T0.minusSeconds(1), null))));
        // ttl 非正
        BusinessException badTtl = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-inv-ttl",
                        List.of("tw.invalid"), null,
                        new WindowRequest(null, null, 0L))));
        // effectiveAt 不早于 expireAt
        BusinessException order = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-inv-order",
                        List.of("tw.invalid"), null,
                        new WindowRequest(T0.plusSeconds(20), T0.plusSeconds(10), null))));
        log.info("DECISION invalid reasons: both={}, past={}, ttl={}, order={}",
                both.getReason(), past.getReason(), badTtl.getReason(), order.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, both.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, past.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, badTtl.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, order.getReason());
    }

    // ---------------------------------------------------------------
    // 8) 无窗口发布单行为不变：永不进入任何自动扫描
    // ---------------------------------------------------------------
    @Test
    void autoRollback_restoresPrePublishValue_identicalSemanticsToManual() {
        // 发布单创建后、发布前，目标配置被改成"将要发布的值"；发布生效后，
        // 自动回滚必须把它恢复成"发布单创建时冻结的值"，与人工回滚完全一致。
        String key = newEntry("tw.restore", "create-time-value");
        String req = "req-tw-restore";
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, T0.plusSeconds(300), null)));
        // 创建发布单之后改值：发布快照记录的是 create-time-value，现值变为 to-be-published
        configService.updateEntry(key,
                new UpdateEntryRequest("to-be-published", null, null, null, null));
        publish(req);
        assertEquals("to-be-published", configService.getEntry(key).value());

        Clock.fixedAt(T0.plusSeconds(301));
        releaseService.sweepExpiredReleases();

        EntryView restored = configService.getEntry(key);
        log.info("DECISION auto restore: value={}, version={} (CREATE=1,UPDATE=2,RESTORE=>3)",
                restored.value(), restored.version());
        assertEquals("create-time-value", restored.value(),
                "自动回滚必须恢复为发布单创建时冻结的值");
        assertEquals(3L, restored.version(), "CREATE=1, 发布前 UPDATE=2, 自动 RESTORE=3");
        assertEquals("AUTO", releaseService.get(req).rollbackTrigger());
    }

    @Test
    void sweepAndManualRollbackCollideAtDue_convergeToSingleTerminalOutcome() throws Exception {
        String key = newEntry("tw.collide", "base-v");
        String req = "req-tw-collide";
        Instant expire = T0.plusSeconds(900);
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expire, null)));
        publish(req);
        Clock.fixedAt(expire);

        // 一边由"到期扫描"触发，一边人工触发，多线程同时发起
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ReleaseView> views = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        // 其中一个线程模拟 sweep（用 autoRollback），其余人工
        for (int i = 0; i < threads; i++) {
            final boolean viaSweep = (i == 0);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    views.add(viaSweep ? releaseService.autoRollback(req)
                            : releaseService.rollback(req));
                } catch (Exception e) {
                    errors.incrementAndGet();
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

        // 再次扫描：候选应为空或全部重放，绝无第二次 RESTORE
        SweepResult again = releaseService.sweepExpiredReleases();
        log.info("DECISION collide: errors={}, states={}, triggers={}, secondSweepScanned={}",
                errors.get(),
                views.stream().map(ReleaseView::state).distinct().toList(),
                views.stream().map(ReleaseView::rollbackTrigger).distinct().toList(),
                again.scanned());
        assertEquals(0, errors.get());
        assertEquals(threads, views.size());
        assertEquals(1, views.stream().map(ReleaseView::state).distinct().count());
        assertEquals(1, views.stream().map(ReleaseView::rollbackTrigger).distinct().count());
        assertEquals("ROLLED_BACK", views.get(0).state());
        assertEquals(2L, configService.getEntry(key).version());
    }

    // ---------------------------------------------------------------
    // 8) 无窗口发布单行为不变：永不进入任何自动扫描
    // ---------------------------------------------------------------

    @Test
    void noWindowRelease_neverAutoRollsBack_evenFarInFuture() {
        String key = newEntry("tw.none", "base-v");
        String req = "req-tw-none";
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null));
        publish(req);
        assertNull(releaseService.get(req).actualExpireAt());
        assertNull(releaseService.get(req).effectiveAt());
        Clock.fixedAt(Instant.parse("2099-01-01T00:00:00Z"));
        SweepResult expired = releaseService.sweepExpiredReleases();
        SweepResult effective = releaseService.sweepEffectiveReleases();
        assertTrue(expired.targets().stream().noneMatch(req::equals),
                "无窗口发布单绝不能出现在到期回滚候选中");
        assertTrue(effective.targets().stream().noneMatch(req::equals),
                "无窗口发布单绝不能出现在生效候选中");
        assertEquals("PUBLISHED", releaseService.get(req).state());
        EntryView entry = configService.getEntry(key);
        log.info("DECISION no-window release unchanged at 2099: state=PUBLISHED version={}", entry.version());
    }
}
