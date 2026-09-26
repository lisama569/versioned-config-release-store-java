package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.api.UpdateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import com.github.lisama569.versionedconfigreleasestorejava.support.Cursors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键集分页边界与"持续写入下不重复、不遗漏"测试。
 * 独立 ApplicationContext，避免与其他测试类共享内存库造成全局窗口干扰。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaginationTest {

    private static final Logger log = LoggerFactory.getLogger(PaginationTest.class);

    @Autowired
    private ConfigService configService;

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    @Test
    void keysetPagination_coversAllRows_withoutDuplicateOrGap() {
        int total = 23;
        String prefix = "page." + UUID.randomUUID() + ".";
        // 全部固定在同一毫秒创建：刻意制造 (createdAt) 相同，检验 seq 决胜。
        // 基准时间取唯一值（带随机秒偏移），避免与其他测试的固定时间窗口重叠。
        // 同一毫秒基准在本次 JVM 内唯一：23 行 createdAt 相同（检验 seq 决胜），
        // 其他测试不可能落在同一毫秒窗口，全量扫描时窗口里恰好只有这 23 行。
        Instant fixed = Instant.now()
                .plusSeconds(365L * 24 * 3600 * 30)
                .plusMillis(System.nanoTime() % 100_000_000L)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Clock.fixedAt(fixed);
        Clock.fixedAt(fixed);
        for (int i = 0; i < total; i++) {
            configService.createEntry(new CreateEntryRequest(prefix + i, "v" + i, null, null));
        }
        log.info("INPUT {} entries all createdAt={}, pageSize=5", total, fixed);

        // 本用例时间戳在未来，其窗口位于全局序列末端。先从首页扫描定位到本用例首行所在页，
        // 再从该窗口起点连续翻页，验证窗口内恰好 23 行、5 页、无重无漏。
        Set<Long> seen = new HashSet<>();
        List<Long> orderedSeqs = new ArrayList<>();
        String cursor = null;
        int pageCount = 0;
        boolean inWindow = false;
        do {
            CursorPage<EntryView> page = configService.listEntries(cursor, 5);
            boolean pageHasOurs = page.items().stream().anyMatch(e -> e.key().startsWith(prefix));
            if (pageHasOurs) {
                inWindow = true;
                pageCount++;
            }
            for (EntryView e : page.items()) {
                if (e.key().startsWith(prefix)) {
                    assertTrue(seen.add(e.seq()), "分页出现重复 seq=" + e.seq());
                    orderedSeqs.add(e.seq());
                } else {
                    // 进入本用例窗口后，页中不允许再混入其他数据（时间戳唯一窗口保证）
                    assertTrue(!inWindow || false,
                            "窗口内混入其他测试数据: " + e.key());
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        long ours = orderedSeqs.size();
        log.info("DECISION windowPages={}, collectedSeqs={}, expected={} (判定依据: 同毫秒 seq 决胜，窗口唯一)",
                pageCount, ours, total);
        assertEquals(total, ours, "不得遗漏");
        for (int i = 1; i < orderedSeqs.size(); i++) {
            assertTrue(orderedSeqs.get(i - 1) < orderedSeqs.get(i), "seq 必须严格递增");
        }
        assertEquals(5, pageCount, "23 条、页大小 5 => 5 页（末页 3 条）");
    }

    @Test
    void lastPageHasNullNextCursor_andExactBoundarySizes() {
        String prefix = "bound." + UUID.randomUUID() + ".";
        Clock.fixedAt(Instant.now()
                .plusSeconds(365L * 24 * 3600 * 31)
                .plusMillis(System.nanoTime() % 100_000_000L)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        for (int i = 0; i < 10; i++) {
            configService.createEntry(new CreateEntryRequest(prefix + i, "v", null, null));
        }
        // 本用例 10 条，页大小 10：首页必满页且有下一页（共享库里后续还有其他数据）
        CursorPage<EntryView> first = configService.listEntries(null, 10);
        // 末页协议：取一个肯定超过全库总量的页大小，返回不足一页且 nextCursor 必须为 null
        CursorPage<EntryView> big = configService.listEntries(null, 100_000);
        log.info("DECISION finalPageSize={} < limit, nextCursor={} (期望 null)",
                big.items().size(), big.nextCursor());
        assertNull(big.nextCursor(), "最后一页 nextCursor 必须为 null");
        assertEquals(10, first.items().size());
        assertNotNull(first.nextCursor(), "满页必须返回下一页游标");
    }

    @Test
    void malformedCursor_isRejectedAsInvalidRequest() {
        log.info("INPUT malformed cursor=not-a-cursor");
        assertThrows(IllegalArgumentException.class,
                () -> configService.listEntries("not-a-cursor", 10));
    }

    @Test
    void cursorEncodeDecode_roundTrip() {
        String c = Cursors.encode(123456789L, 42L);
        long[] decoded = Cursors.decode(c);
        assertEquals(123456789L, decoded[0]);
        assertEquals(42L, decoded[1]);
        log.info("DECISION cursor round-trip {} -> [{},{}]", c, decoded[0], decoded[1]);
    }

    @Test
    void revisionPagination_matchesUpdateHistoryExactly() {
        String key = "rev." + UUID.randomUUID();
        configService.createEntry(new CreateEntryRequest(key, "v0", null, null));
        int updates = 7;
        for (int i = 1; i <= updates; i++) {
            configService.updateEntry(key,
                    new UpdateEntryRequest("v" + i, null, null, (long) i, null));
        }
        List<Long> revSeqs = new ArrayList<>();
        String cursor = null;
        do {
            var page = configService.listRevisions(key, cursor, 3);
            page.items().forEach(r -> revSeqs.add(r.seq()));
            cursor = page.nextCursor();
        } while (cursor != null);
        log.info("DECISION revisions collected={}, expected={} (1 CREATE + {} UPDATE)",
                revSeqs.size(), updates + 1, updates);
        assertEquals(updates + 1, revSeqs.size());
        assertEquals(updates + 1L, configService.getEntry(key).version());
    }

    @Test
    void paginationStable_whileWritesContinue() {
        // 确定性验证"翻页过程中有新写入"：
        // 1) 用早于其他测试的固定时间戳创建 30 行，它们必然占据全局最前 3 页；
        // 2) 取走前 3 页并保存第 3 页末游标；
        // 3) 再创建 30 行（时间戳更晚）；
        // 4) 从保存的游标继续，最终本用例 60 行必须不重不漏。
        String prefix = "live." + UUID.randomUUID() + ".";
        Clock.fixedAt(Instant.parse("2020-01-01T00:00:00Z"));
        int initial = 30;
        for (int i = 0; i < initial; i++) {
            configService.createEntry(new CreateEntryRequest(prefix + i, "v", null, null));
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int p = 0; p < 3; p++) {
            var page = configService.listEntries(cursor, 10);
            assertEquals(10, page.items().size(), "前 30 行应严格占满前 3 页");
            page.items().forEach(e -> {
                assertTrue(e.key().startsWith(prefix), "前 3 页应只含本用例行: " + e.key());
                assertTrue(seen.add(e.key()), "重复行: " + e.key());
            });
            cursor = page.nextCursor();
        }
        assertNotNull(cursor, "30 行之后必须还有下一页游标");

        // 翻页进行到一半时，继续写入 30 行（时间戳更晚，必然位于游标之后）
        Clock.fixedAt(Instant.parse("2020-01-02T00:00:00Z"));
        for (int i = initial; i < initial + 30; i++) {
            configService.createEntry(new CreateEntryRequest(prefix + i, "v", null, null));
        }

        while (cursor != null) {
            var page = configService.listEntries(cursor, 10);
            for (var e : page.items()) {
                if (e.key().startsWith(prefix)) {
                    assertTrue(seen.add(e.key()), "持续写入下出现重复行: " + e.key());
                }
            }
            cursor = page.nextCursor();
        }
        log.info("DECISION entriesWithPrefix={}, expected=60 (判定依据: 新写入 createdAt 更晚，落在已取游标之后)",
                seen.size());
        assertEquals(60, seen.size(), "持续写入下完整遍历必须覆盖全部 60 条且无重复");
    }
}
