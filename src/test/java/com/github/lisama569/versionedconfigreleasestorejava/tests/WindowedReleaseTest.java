package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateWindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigRevision;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigRevisionRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.service.error.ConflictReason;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生效时间窗与到期自动回滚测试。
 *
 * <p>所有用例通过 {@link Clock#fixedAt(Instant)} 固定时间源，
 * 不依赖真实等待，离线可重复。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WindowedReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(WindowedReleaseTest.class);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ConfigRevisionRepository revisionRepository;

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    @Test
    void expiredWindow_isAutoRolledBack_withRestoredValueAndTraceableRevision() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.auto", "before-release", null, null));
        ReleaseView created = releaseService.create(new CreateReleaseRequest(
                "req-w-auto", List.of("k.w.auto"), null, null, null, 60L));
        log.info("INPUT create with effectiveForSeconds=60: windowEnd(before publish)={}",
                created.windowEnd());
        assertNull(created.windowEnd(), "仅声明时长时，终点在发布瞬间才结算");
        releaseService.submit("req-w-auto");
        // 发布单创建后又改了一次值，回滚应恢复为创建时冻结值
        configService.updateEntry("k.w.auto",
                new UpdateEntryRequest("after-release-created", null, null, null, null));
        ReleaseView published = releaseService.publish("req-w-auto");
        log.info("DECISION publish settled windowEnd={} (判定依据: T0 + 60s)", published.windowEnd());
        assertEquals(T0.plusSeconds(60), published.windowEnd());

        // 窗口未到：清扫不应触碰
        Clock.fixedAt(T0.plusSeconds(59));
        assertEquals(0, releaseService.processExpiredWindows());
        assertEquals("PUBLISHED", releaseService.get("req-w-auto").state());

        // 窗口到期：自动回滚
        Clock.fixedAt(T0.plusSeconds(60));
        int rolledBack = releaseService.processExpiredWindows();
        ReleaseView after = releaseService.get("req-w-auto");
        EntryView entry = configService.getEntry("k.w.auto");
        log.info("DECISION auto-rollback rolledBack={}, state={}, reason={}, value={}, version={}",
                rolledBack, after.state(), after.rollbackReason(), entry.value(), entry.version());
        assertEquals(1, rolledBack);
        assertEquals("ROLLED_BACK", after.state());
        assertEquals("AUTO_EXPIRED", after.rollbackReason());
        assertEquals("before-release", entry.value(), "恢复为发布单创建时冻结的值");
        assertEquals(3, entry.version(), "CREATE=1, UPDATE=2, RESTORE=3");

        List<ConfigRevision> revisions = revisionRepository
                .findByEntrySeqOrderBySeqAsc(entry.seq());
        ConfigRevision restore = revisions.get(revisions.size() - 1);
        log.info("DECISION restore revision: type={}, operator={}, {}->{}",
                restore.getChangeType(), restore.getOperator(),
                restore.getVersionFrom(), restore.getVersionTo());
        assertEquals("RESTORE", restore.getChangeType().name());
        assertEquals("system-auto-rollback:req-w-auto", restore.getOperator());
    }

    @Test
    void manualRollbackBeforeExpiry_thenAutoTrigger_isNoOpReplayWithSameConclusion() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.manual", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-manual", List.of("k.w.manual"), null, null, null, 60L));
        releaseService.submit("req-w-manual");
        releaseService.publish("req-w-manual");

        // 窗口未到期就手动回滚
        Clock.fixedAt(T0.plusSeconds(30));
        ReleaseView manual = releaseService.rollback("req-w-manual");
        log.info("DECISION manual rollback before expiry: state={}, reason={}",
                manual.state(), manual.rollbackReason());
        assertEquals("ROLLED_BACK", manual.state());
        assertEquals("MANUAL", manual.rollbackReason());

        // 到期后自动触发：必须是幂等重放，结论与首次一致，不二次生效
        Clock.fixedAt(T0.plusSeconds(61));
        ReleaseView auto = releaseService.rollbackAuto("req-w-manual");
        EntryView entry = configService.getEntry("k.w.manual");
        log.info("DECISION auto trigger after manual: state={}, reason={}, entryVersion={}",
                auto.state(), auto.rollbackReason(), entry.version());
        assertEquals("ROLLED_BACK", auto.state());
        assertEquals("MANUAL", auto.rollbackReason(), "回滚来源仍是首次的手动回滚");
        assertEquals(manual.seq(), auto.seq());
        assertEquals(2, entry.version(), "RESTORE 只发生一次");
        assertEquals(0, releaseService.processExpiredWindows(), "已回滚的单不再被清扫");
    }

    @Test
    void concurrentManualAndAutoRollback_exactlyOnce_allCallersSeeSameConclusion() throws Exception {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.race", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-race", List.of("k.w.race"), null, null, null, 60L));
        releaseService.submit("req-w-race");
        releaseService.publish("req-w-race");

        Clock.fixedAt(T0.plusSeconds(61)); // 已到期
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ReleaseView> returned = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean manual = i % 2 == 0;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                // 手动与自动回滚并发撞同一发布单
                returned.add(manual
                        ? releaseService.rollback("req-w-race")
                        : releaseService.rollbackAuto("req-w-race"));
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        long distinctSeq = returned.stream().map(ReleaseView::seq).distinct().count();
        long distinctReason = returned.stream().map(ReleaseView::rollbackReason).distinct().count();
        EntryView entry = configService.getEntry("k.w.race");
        log.info("DECISION concurrent rollback: calls={}, distinctSeq={}, distinctReason={}, "
                + "entryVersion={}", returned.size(), distinctSeq, distinctReason, entry.version());
        assertEquals(threads, returned.size(), "所有并发调用都应成功返回（重放不报错）");
        assertEquals(1, distinctSeq);
        assertTrue(returned.stream().allMatch(v -> "ROLLED_BACK".equals(v.state())));
        assertEquals(1, distinctReason, "所有调用看到同一个回滚来源（谁先提交算谁的）");
        assertEquals(2, entry.version(), "回滚只真正生效一次：CREATE=1, RESTORE=2");
    }

    @Test
    void repeatedAutoTriggerAfterExpiry_idempotentReplay() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.repeat", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-repeat", List.of("k.w.repeat"), null, null, null, 60L));
        releaseService.submit("req-w-repeat");
        releaseService.publish("req-w-repeat");

        Clock.fixedAt(T0.plusSeconds(120));
        ReleaseView first = releaseService.rollbackAuto("req-w-repeat");
        ReleaseView second = releaseService.rollbackAuto("req-w-repeat");
        int swept = releaseService.processExpiredWindows();
        EntryView entry = configService.getEntry("k.w.repeat");
        log.info("DECISION repeated auto trigger: first={}, second={}, swept={}, version={}",
                first.state(), second.state(), swept, entry.version());
        assertEquals("ROLLED_BACK", first.state());
        assertEquals("ROLLED_BACK", second.state());
        assertEquals("AUTO_EXPIRED", second.rollbackReason());
        assertEquals(0, swept, "已回滚后清扫不再处理");
        assertEquals(2, entry.version());
    }

    @Test
    void autoTriggerBeforeExpiry_orWithoutWindow_isRejectedWithDistinctReason() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.early", "v", null, null));
        configService.createEntry(new CreateEntryRequest("k.w.nowin", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-early", List.of("k.w.early"), null, null, null, 3600L));
        releaseService.submit("req-w-early");
        releaseService.publish("req-w-early");
        releaseService.create(new CreateReleaseRequest("req-w-nowin", List.of("k.w.nowin"), null));
        releaseService.submit("req-w-nowin");
        releaseService.publish("req-w-nowin");

        log.info("INPUT auto-rollback on not-yet-expired release req-w-early");
        BusinessException early = assertThrows(BusinessException.class,
                () -> releaseService.rollbackAuto("req-w-early"));
        log.info("DECISION reason={}", early.getReason());
        assertEquals(ConflictReason.WINDOW_NOT_EXPIRED, early.getReason());

        log.info("INPUT auto-rollback on release without any window req-w-nowin");
        BusinessException noWindow = assertThrows(BusinessException.class,
                () -> releaseService.rollbackAuto("req-w-nowin"));
        log.info("DECISION reason={}", noWindow.getReason());
        assertEquals(ConflictReason.WINDOW_NOT_EXPIRED, noWindow.getReason());

        // 无时间窗的发布单永不参与自动回滚；有时间窗的到期即被清扫
        Clock.fixedAt(T0.plusSeconds(365L * 24 * 3600));
        int swept = releaseService.processExpiredWindows();
        log.info("DECISION far-future sweep: swept={} (req-w-early 的 3600s 窗口早已到期), "
                + "req-w-nowin state={}", swept, releaseService.get("req-w-nowin").state());
        assertEquals(1, swept, "只有 req-w-early 到期被自动回滚");
        assertEquals("ROLLED_BACK", releaseService.get("req-w-early").state());
        assertEquals("AUTO_EXPIRED", releaseService.get("req-w-early").rollbackReason());
        assertEquals("PUBLISHED", releaseService.get("req-w-nowin").state(),
                "无时间窗的发布单永不自动回滚");
    }

    @Test
    void invalidWindowParams_areRejectedWithDistinctReason() {
        configService.createEntry(new CreateEntryRequest("k.w.bad", "v", null, null));
        Clock.fixedAt(T0);

        BusinessException both = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-w-bad1",
                        List.of("k.w.bad"), null, null,
                        T0.plusSeconds(3600), 60L)));
        log.info("DECISION until+duration both set: reason={}", both.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, both.getReason());

        BusinessException nonPositive = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-w-bad2",
                        List.of("k.w.bad"), null, null, null, 0L)));
        log.info("DECISION non-positive duration: reason={}", nonPositive.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, nonPositive.getReason());

        BusinessException endBeforeStart = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest("req-w-bad3",
                        List.of("k.w.bad"), null,
                        T0.plusSeconds(3600), T0.plusSeconds(1800), null)));
        log.info("DECISION end<=start: reason={}", endBeforeStart.getReason());
        assertEquals(ConflictReason.INVALID_WINDOW, endBeforeStart.getReason());
    }

    @Test
    void updateAndClearWindow_beforeExpiry_haveDeterministicEffect() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.upd", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-upd", List.of("k.w.upd"), null, null, null, 60L));
        releaseService.submit("req-w-upd");
        releaseService.publish("req-w-upd");

        // 到点前重新设置：已发布单按"当前时刻+时长"立即结算新终点
        Clock.fixedAt(T0.plusSeconds(30));
        ReleaseView updated = releaseService.updateWindow("req-w-upd",
                new UpdateWindowRequest(null, null, 3600L));
        log.info("DECISION window updated: windowEnd={} (判定依据: T0+30s+3600s)", updated.windowEnd());
        assertEquals(T0.plusSeconds(30).plusSeconds(3600), updated.windowEnd());

        // 旧终点已过，但新终点未到：不清扫
        Clock.fixedAt(T0.plusSeconds(61));
        assertEquals(0, releaseService.processExpiredWindows());
        assertEquals("PUBLISHED", releaseService.get("req-w-upd").state());

        // 取消时间窗：永不自动回滚
        ReleaseView cleared = releaseService.clearWindow("req-w-upd");
        assertNull(cleared.windowEnd());
        Clock.fixedAt(T0.plusSeconds(10L * 365 * 24 * 3600));
        assertEquals(0, releaseService.processExpiredWindows());
        assertEquals("PUBLISHED", releaseService.get("req-w-upd").state());
        log.info("DECISION cleared window: far-future sweep leaves release PUBLISHED");
    }

    @Test
    void windowChange_onRolledBackRelease_isRejected() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.term", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-term", List.of("k.w.term"), null, null, null, 60L));
        releaseService.submit("req-w-term");
        releaseService.publish("req-w-term");
        releaseService.rollback("req-w-term");

        BusinessException upd = assertThrows(BusinessException.class,
                () -> releaseService.updateWindow("req-w-term",
                        new UpdateWindowRequest(null, null, 60L)));
        log.info("DECISION update window on terminal release: reason={}", upd.getReason());
        assertEquals(ConflictReason.ILLEGAL_STATE_TRANSITION, upd.getReason());

        BusinessException clr = assertThrows(BusinessException.class,
                () -> releaseService.clearWindow("req-w-term"));
        assertEquals(ConflictReason.ILLEGAL_STATE_TRANSITION, clr.getReason());
    }

    @Test
    void windowResetToPast_makesReleaseExpireOnNextSweep() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.past", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-past", List.of("k.w.past"), null, null, null, 3600L));
        releaseService.submit("req-w-past");
        releaseService.publish("req-w-past");

        // 把终点重设到"现在之前"：确定的结果是下一轮清扫即自动回滚
        Clock.fixedAt(T0.plusSeconds(100));
        releaseService.updateWindow("req-w-past",
                new UpdateWindowRequest(null, T0.plusSeconds(50), null));
        int rolledBack = releaseService.processExpiredWindows();
        ReleaseView after = releaseService.get("req-w-past");
        log.info("DECISION window reset to past: swept={}, state={}, reason={}",
                rolledBack, after.state(), after.rollbackReason());
        assertEquals(1, rolledBack);
        assertEquals("ROLLED_BACK", after.state());
        assertEquals("AUTO_EXPIRED", after.rollbackReason());
    }

    @Test
    void createReplayWithDifferentWindow_isRejectedAsIdempotencyMismatch() {
        Clock.fixedAt(T0);
        configService.createEntry(new CreateEntryRequest("k.w.idem", "v", null, null));
        releaseService.create(new CreateReleaseRequest(
                "req-w-idem", List.of("k.w.idem"), null, null, null, 60L));

        ReleaseView replay = releaseService.create(new CreateReleaseRequest(
                "req-w-idem", List.of("k.w.idem"), null, null, null, 60L));
        log.info("DECISION identical replay: seq={}, windowEnd={}", replay.seq(), replay.windowEnd());
        assertEquals("DRAFT", replay.state());

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> releaseService.create(new CreateReleaseRequest(
                        "req-w-idem", List.of("k.w.idem"), null, null, null, 120L)));
        log.info("DECISION replay with different window: reason={}", mismatch.getReason());
        assertEquals(ConflictReason.IDEMPOTENCY_KEY_MISMATCH, mismatch.getReason());
    }
}
