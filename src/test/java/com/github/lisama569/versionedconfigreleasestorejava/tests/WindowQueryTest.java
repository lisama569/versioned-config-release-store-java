package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间窗维度分页查询与历史数据兼容测试。
 *
 * <p>窗口查询是全局扫描，方法间必须彻底隔离数据，
 * 因此每个测试方法后重建 ApplicationContext（独立命名内存库）。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WindowQueryTest {

    private static final Logger log = LoggerFactory.getLogger(WindowQueryTest.class);
    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    private String publishWithWindowEnd(String requestId, String key, Instant windowEnd) {
        configService.createEntry(new CreateEntryRequest(key, "v-" + key, null, null));
        releaseService.create(new CreateReleaseRequest(
                requestId, List.of(key), null, null, windowEnd, null));
        releaseService.submit(requestId);
        releaseService.publish(requestId);
        return requestId;
    }

    private static List<String> drain(CursorPage<ReleaseView> first,
                                      Function<String, CursorPage<ReleaseView>> next) {
        List<String> ids = new ArrayList<>();
        CursorPage<ReleaseView> page = first;
        while (true) {
            page.items().forEach(v -> ids.add(v.requestId()));
            if (page.nextCursor() == null) {
                return ids;
            }
            page = next.apply(page.nextCursor());
        }
    }

    @Test
    void expiringSoon_filtersByWithinSeconds_andPaginatesExactly() {
        Clock.fixedAt(T0);
        for (int i = 1; i <= 5; i++) {
            publishWithWindowEnd("req-es-" + i, "k.es." + i, T0.plusSeconds(i * 100L));
        }
        // withinSeconds=250 -> 只有终点 <= T0+250 的两张单
        CursorPage<ReleaseView> first = releaseService.listByWindow(
                "EXPIRING_SOON", null, 1, 250L);
        log.info("DECISION expiring-soon page1: items={}, nextCursor={}",
                first.items().stream().map(ReleaseView::requestId).toList(), first.nextCursor());
        assertEquals(1, first.items().size());
        assertEquals("req-es-1", first.items().get(0).requestId());

        List<String> ids = drain(first,
                c -> releaseService.listByWindow("EXPIRING_SOON", c, 1, 250L));
        log.info("DECISION expiring-soon within=250 drained: {}", ids);
        assertEquals(List.of("req-es-1", "req-es-2"), ids);

        // 默认范围（3600s）覆盖全部 5 张，分页无重复无遗漏
        List<String> all = drain(releaseService.listByWindow("EXPIRING_SOON", null, 2, null),
                c -> releaseService.listByWindow("EXPIRING_SOON", c, 2, null));
        log.info("DECISION expiring-soon default-within drained: {}", all);
        assertEquals(List.of("req-es-1", "req-es-2", "req-es-3", "req-es-4", "req-es-5"), all);
    }

    @Test
    void expiredPending_paginationStable_whileNewExpiredReleasesAreWritten() {
        Clock.fixedAt(T0);
        for (int i = 1; i <= 3; i++) {
            publishWithWindowEnd("req-ep-" + i, "k.ep." + i, T0.plusSeconds(60));
        }
        Clock.fixedAt(T0.plusSeconds(61)); // 全部到期

        CursorPage<ReleaseView> page1 = releaseService.listByWindow(
                "EXPIRED_PENDING", null, 2, null);
        log.info("DECISION expired-pending page1: items={}, nextCursor={}",
                page1.items().stream().map(ReleaseView::requestId).toList(), page1.nextCursor());
        assertEquals(List.of("req-ep-1", "req-ep-2"),
                page1.items().stream().map(ReleaseView::requestId).toList());

        // 翻页间隙持续写入新的已到期发布单（终点甚至早于游标单）
        Clock.fixedAt(T0.plusSeconds(62));
        publishWithWindowEnd("req-ep-4", "k.ep.4", T0.plusSeconds(30));
        publishWithWindowEnd("req-ep-5", "k.ep.5", T0.plusSeconds(10));

        List<String> rest = drain(
                releaseService.listByWindow("EXPIRED_PENDING", page1.nextCursor(), 2, null),
                c -> releaseService.listByWindow("EXPIRED_PENDING", c, 2, null));
        log.info("DECISION expired-pending remaining pages: {}", rest);

        Set<String> all = new LinkedHashSet<>(
                List.of("req-ep-1", "req-ep-2"));
        all.addAll(rest);
        log.info("DECISION expired-pending total drained: {}", all);
        assertEquals(Set.of("req-ep-1", "req-ep-2", "req-ep-3", "req-ep-4", "req-ep-5"), all,
                "持续写入下翻页不重复、不遗漏");
        assertEquals(3, rest.size());
    }

    @Test
    void autoRolledBack_listsOnlyAutoRollbacks_andPaginates() {
        Clock.fixedAt(T0);
        publishWithWindowEnd("req-ar-1", "k.ar.1", T0.plusSeconds(60));
        publishWithWindowEnd("req-ar-2", "k.ar.2", T0.plusSeconds(60));
        publishWithWindowEnd("req-ar-3", "k.ar.3", T0.plusSeconds(60));
        // req-ar-3 手动回滚：不得出现在 AUTO_ROLLED_BACK 里
        releaseService.rollback("req-ar-3");

        Clock.fixedAt(T0.plusSeconds(61));
        int swept = releaseService.processExpiredWindows();
        log.info("DECISION swept={} (req-ar-3 已手动回滚，不参与)", swept);
        assertEquals(2, swept);

        List<String> ids = drain(releaseService.listByWindow("AUTO_ROLLED_BACK", null, 1, null),
                c -> releaseService.listByWindow("AUTO_ROLLED_BACK", c, 1, null));
        log.info("DECISION auto-rolled-back drained: {}", ids);
        assertEquals(List.of("req-ar-1", "req-ar-2"), ids);

        // 已自动回滚的不再出现在"已到期未回滚"
        List<String> pending = drain(releaseService.listByWindow("EXPIRED_PENDING", null, 10, null),
                c -> releaseService.listByWindow("EXPIRED_PENDING", c, 10, null));
        log.info("DECISION expired-pending after sweep: {}", pending);
        assertTrue(pending.isEmpty());
    }

    @Test
    void legacyReleaseRow_withoutWindowColumns_hasStableCompatibleMeaning() {
        Clock.fixedAt(T0);
        // 模拟本轮改动之前写入的历史发布单：只含旧列，时间窗四列物理上为 NULL
        jdbcTemplate.update("""
                INSERT INTO release_order
                    (request_id, state, remark, format_version,
                     created_at, updated_at, published_at, optimistic_version)
                VALUES ('req-legacy-nowin', 'PUBLISHED', 'legacy', 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """);

        ReleaseView view = releaseService.get("req-legacy-nowin");
        log.info("DECISION legacy release view: windowStart={}, windowEnd={}, "
                + "effectiveForSeconds={}, rollbackReason={}",
                view.windowStart(), view.windowEnd(), view.effectiveForSeconds(),
                view.rollbackReason());
        assertNull(view.windowStart());
        assertNull(view.windowEnd());
        assertNull(view.effectiveForSeconds());
        assertNull(view.rollbackReason());

        // 永不参与自动回滚
        Clock.fixedAt(T0.plusSeconds(10L * 365 * 24 * 3600));
        assertEquals(0, releaseService.processExpiredWindows());
        assertEquals("PUBLISHED", releaseService.get("req-legacy-nowin").state());

        // 不出现在任何时间窗维度查询里
        for (String status : List.of("EXPIRING_SOON", "EXPIRED_PENDING", "AUTO_ROLLED_BACK")) {
            List<String> ids = drain(releaseService.listByWindow(status, null, 50, null),
                    c -> releaseService.listByWindow(status, c, 50, null));
            log.info("DECISION legacy row in {} query: {}", status, ids);
            assertTrue(ids.isEmpty(), "历史无窗发布单不应出现在 " + status);
        }
    }

    @Test
    void invalidStatusAndMalformedCursor_areRejected() {
        Clock.fixedAt(T0);
        log.info("INPUT window query with unknown status");
        assertThrows(IllegalArgumentException.class,
                () -> releaseService.listByWindow("NO_SUCH_STATUS", null, 10, null));
        log.info("INPUT window query with malformed cursor");
        assertThrows(IllegalArgumentException.class,
                () -> releaseService.listByWindow("EXPIRED_PENDING", "garbage", 10, null));
        log.info("DECISION unknown status / malformed cursor both rejected as invalid request");
    }
}
