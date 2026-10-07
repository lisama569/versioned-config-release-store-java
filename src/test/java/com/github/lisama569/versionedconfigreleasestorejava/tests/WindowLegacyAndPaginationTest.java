package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ReleaseRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.WindowBucket;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间窗的历史数据兼容与按时间窗维度的键集分页边界测试。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WindowLegacyAndPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(WindowLegacyAndPaginationTest.class);

    private static final Instant T0 = Instant.parse("2026-10-08T01:00:00Z");

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ReleaseRepository releaseRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void fixClock() {
        Clock.fixedAt(T0);
    }

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    private String entry(String key) {
        configService.createEntry(new CreateEntryRequest(key, "v", null, null));
        return key;
    }

    private long publishWithExpiry(String req, String key, Instant expireAt) {
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, expireAt, null)));
        releaseService.submit(req);
        releaseService.publish(req);
        return releaseService.get(req).seq();
    }

    // ---------------------------------------------------------------
    // 历史兼容：旧发布单（窗口列全 NULL、旧回滚行 trigger NULL）
    // ---------------------------------------------------------------

    @Test
    void legacyRelease_withoutWindowColumns_readsAsNoWindow_andNeverSwept() {
        String key = "legacy.win." + System.nanoTime();
        entry(key);
        String req = "legacy-rel-" + System.nanoTime();
        // 用原生 JDBC 造一行"本特性上线前"的发布单：不提供任何窗口列
        jdbcTemplate.update("""
                        INSERT INTO release_order
                            (request_id, state, remark, format_version,
                             created_at, updated_at, published_at, optimistic_version)
                        VALUES (?, 'PUBLISHED', NULL, 1, ?, ?, ?, 0)
                        """,
                req, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0),
                java.sql.Timestamp.from(T0));
        jdbcTemplate.update("""
                        INSERT INTO release_item
                            (release_seq, entry_key, value_at_create, value_version_at_create,
                             value_version_at_publish)
                        VALUES (?, ?, 'v', 1, 1)
                        """, releaseRepository.findByRequestId(req).orElseThrow().getSeq(), key);

        String rawEff = jdbcTemplate.queryForObject(
                "SELECT effective_at FROM release_order WHERE request_id = ?", String.class, req);
        String rawExp = jdbcTemplate.queryForObject(
                "SELECT expire_at FROM release_order WHERE request_id = ?", String.class, req);
        String rawTrigger = jdbcTemplate.queryForObject(
                "SELECT rollback_trigger FROM release_order WHERE request_id = ?", String.class, req);
        log.info("RAW legacy release: effectiveAt={}, expireAt={}, rollbackTrigger={}",
                rawEff, rawExp, rawTrigger);
        assertNull(rawEff);
        assertNull(rawExp);
        assertNull(rawTrigger);

        ReleaseView view = releaseService.get(req);
        assertNull(view.effectiveAt());
        assertNull(view.expireAt());
        assertNull(view.ttlSeconds());
        assertNull(view.actualExpireAt());
        assertNull(view.rollbackTrigger(), "仍在 PUBLISHED 的历史行 trigger 应为 null");
        log.info("DECISION legacy no-window read stable: actualExpireAt=null, trigger=null");

        // 即便推到很远的未来，也绝不出现在到期候选
        Clock.fixedAt(Instant.parse("2099-01-01T00:00:00Z"));
        var sweep = releaseService.sweepExpiredReleases();
        assertTrue(sweep.targets().stream().noneMatch(req::equals),
                "历史无窗口发布单永远不参与自动回滚");
    }

    @Test
    void legacyManualRollback_nullTriggerColumn_normalizesToManual() {
        String key = "legacy.rb." + System.nanoTime();
        entry(key);
        String req = "legacy-rb-" + System.nanoTime();
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null));
        releaseService.submit(req);
        releaseService.publish(req);
        releaseService.rollback(req);
        // 模拟历史回滚行：把 trigger 列抹成 NULL（旧系统只有人工回滚）
        jdbcTemplate.update("UPDATE release_order SET rollback_trigger = NULL WHERE request_id = ?",
                req);

        ReleaseView view = releaseService.get(req);
        assertEquals("ROLLED_BACK", view.state());
        assertEquals("MANUAL", view.rollbackTrigger(),
                "trigger 列为 NULL 的历史回滚必须稳定归一化为 MANUAL");
        log.info("DECISION legacy null trigger normalized: {}", view.rollbackTrigger());
    }

    // ---------------------------------------------------------------
    // 分页边界：即将到期 / 已到期未回滚 / 已自动回滚，持续写入不重不漏
    // ---------------------------------------------------------------

    @Test
    void expiringPagination_pagesByExpireAt_boundaryAndNoDuplicateOrMiss() {
        String base = "wp.exp." + System.nanoTime() + ".";
        int total = 7;
        List<Long> expectedSeqs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            expectedSeqs.add(publishWithExpiry(base + i, entry(base + i),
                    T0.plusSeconds(100 + i))); // 到期时刻互不相同
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorPage<ReleaseView> page =
                    releaseService.listWindow(WindowBucket.EXPIRING, cursor, 3);
            pages++;
            for (ReleaseView v : page.items()) {
                if (v.requestId().startsWith(base)) {
                    seen.add(v.seq());
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        log.info("DECISION EXPIRING paging: pages={}, collected of ours={}, expected={}",
                pages, seen.size(), total);
        assertEquals(total, seen.size(), "不重不漏");
        assertEquals(expectedSeqs, seen, "必须按 expireAt 升序返回");
        assertEquals(3, pages, "7 条 / 页大小 3 => 3 页");
    }

    @Test
    void expiringPagination_newWritesDuringTraversal_areNotDuplicatedOrMissed() {
        String base = "wp.live." + System.nanoTime() + ".";
        publishWithExpiry(base + 0, entry(base + 0), T0.plusSeconds(50));
        publishWithExpiry(base + 1, entry(base + 1), T0.plusSeconds(60));

        // 首页
        CursorPage<ReleaseView> first =
                releaseService.listWindow(WindowBucket.EXPIRING, null, 1);
        assertEquals(1, first.items().size());
        assertEquals(base + 0, first.items().get(0).requestId());
        String cursor = first.nextCursor();

        // 遍历期间持续写入新的在途发布单（到期时刻可能更早/更晚）
        publishWithExpiry(base + "late-a", entry(base + "late-a"), T0.plusSeconds(10));
        publishWithExpiry(base + "late-b", entry(base + "late-b"), T0.plusSeconds(55));

        List<String> rest = new ArrayList<>();
        while (cursor != null) {
            CursorPage<ReleaseView> p =
                    releaseService.listWindow(WindowBucket.EXPIRING, cursor, 1);
            p.items().stream().map(ReleaseView::requestId)
                    .filter(id -> id.startsWith(base)).forEach(rest::add);
            cursor = p.nextCursor();
        }
        // 冻结宇宙语义：遍历开始前已存在的两条必须恰好取全，不重复；
        // 遍历途中写入的行不影响本次遍历（下一轮全新遍历才纳入）。
        log.info("DECISION live traversal remainder after new writes: {}", rest);
        assertTrue(rest.contains(base + 1));
        assertFalse(rest.contains(base + 0), "首页取过的不能重复");
        assertEquals(1, rest.stream().filter(id -> id.equals(base + 1)).count());
    }

    @Test
    void overdueBucket_listsOnlyExpiredAndUnrolledBack() {
        String base = "wp.over." + System.nanoTime() + ".";
        publishWithExpiry(base + "due1", entry(base + "due1"), T0.plusSeconds(30));
        publishWithExpiry(base + "due2", entry(base + "due2"), T0.plusSeconds(50));
        publishWithExpiry(base + "future", entry(base + "future"), T0.plusSeconds(100));
        // 一条到期后被自动回滚：不应出现在 OVERDUE
        String rolledReq = base + "rolled";
        publishWithExpiry(rolledReq, entry(rolledReq), T0.plusSeconds(40));

        // 推进时钟到 60s：due1(30)/due2(50)/rolled(40) 到期，future(100) 未到期
        Clock.fixedAt(T0.plusSeconds(60));
        releaseService.autoRollback(rolledReq);

        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ReleaseView> p =
                    releaseService.listWindow(WindowBucket.OVERDUE, cursor, 50);
            p.items().stream().map(ReleaseView::requestId)
                    .filter(id -> id.startsWith(base)).forEach(ids::add);
            cursor = p.nextCursor();
        } while (cursor != null);

        log.info("DECISION OVERDUE ids={} (判定依据: expireAt<=now 且仍 PUBLISHED)", ids);
        assertTrue(ids.contains(base + "due1"));
        assertTrue(ids.contains(base + "due2"));
        assertFalse(ids.contains(base + "future"), "未到期不能进入 OVERDUE");
        assertFalse(ids.contains(rolledReq), "已回滚不能进入 OVERDUE");
        // 按 expireAt 升序
        assertTrue(ids.indexOf(base + "due1") < ids.indexOf(base + "due2"));
    }

    @Test
    void autoRolledBackBucket_listsOnlyAutoTriggeredOrderedBySeq() {
        String base = "wp.auto." + System.nanoTime() + ".";
        String a = base + "a";
        String b = base + "b";
        publishWithExpiry(a, entry(a), T0.plusSeconds(100));
        publishWithExpiry(b, entry(b), T0.plusSeconds(200));
        // 一条人工回滚（带窗口）：不应出现在 AUTO_ROLLED_BACK
        String manual = base + "manual";
        publishWithExpiry(manual, entry(manual), T0.plusSeconds(300));
        releaseService.rollback(manual);

        Clock.fixedAt(T0.plusSeconds(400));
        releaseService.sweepExpiredReleases();

        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ReleaseView> p = releaseService
                    .listWindow(WindowBucket.AUTO_ROLLED_BACK, cursor, 1);
            p.items().stream().map(ReleaseView::requestId)
                    .filter(id -> id.startsWith(base)).forEach(ids::add);
            cursor = p.nextCursor();
        } while (cursor != null);

        log.info("DECISION AUTO_ROLLED_BACK ids={} (仅 trigger=AUTO, 按 seq 升序)", ids);
        assertEquals(List.of(a, b), ids, "只含自动回滚，且按创建 seq 升序，不重不漏");
        assertFalse(ids.contains(manual), "人工回滚不得混入 AUTO_ROLLED_BACK");
    }

    @Test
    void malformedWindowCursor_isRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> releaseService.listWindow(WindowBucket.EXPIRING, "not-a-cursor", 10));
        log.info("DECISION malformed window cursor rejected: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("malformed"));
    }
}
