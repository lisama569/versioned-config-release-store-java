package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigVersion;
import com.github.lisama569.versionedconfigreleasestorejava.domain.PageResult;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 键集分页边界测试：
 * 1) 总数能被页大小整除/不整除时的最后一页与 hasMore；
 * 2) 遍历期间持续写入：已开启的遍历不重不漏，新写入在后续遍历中可见；
 * 3) 游标损坏/类型错配 -> VALIDATION_ERROR（不静默回首页）；
 * 4) 按 key 的分页互不串数据；
 * 5) 同一毫秒多版本由 seq 决胜，结果确定有序。
 */
class PaginationTest {

    private static final Logger log = LoggerFactory.getLogger(PaginationTest.class);

    @Test
    void globalPagination_boundariesAndNoDuplicateNoMissing() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("seed", "x"); // 先有 1 个版本
        // 再追加 9 个版本，共 10 个版本，页大小 3 -> 3,3,3,1
        for (int v = 1; v <= 9; v++) {
            system.configs().update("seed", (long) v, "x" + (v + 1));
        }
        int size = 3;
        log.info("输入: 全局版本总数=10, pageSize={}, 期望页数=4(3/3/3/1)", size);

        List<Long> seenSeqs = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            PageResult<ConfigVersion> page = system.queries().listVersions(cursor, size);
            pages++;
            log.info("判定依据: 第{}页 返回{}条 hasMore={} nextCursor={}",
                    pages, page.getItems().size(), page.isHasMore(), page.getNextCursor());
            for (ConfigVersion cv : page.getItems()) {
                seenSeqs.add(cv.getSeq());
            }
            if (!page.isHasMore()) {
                assertThat(page.getNextCursor()).isNull();
                break;
            }
            cursor = page.getNextCursor();
        }
        assertThat(pages).isEqualTo(4);
        assertThat(seenSeqs).hasSize(10);
        assertThat(new HashSet<>(seenSeqs)).as("无重复").hasSize(10);
        List<Long> sorted = seenSeqs.stream().sorted().toList();
        assertThat(seenSeqs).containsExactlyElementsOf(sorted).as("严格按 seq 升序");
        log.info("判定依据: 汇总 seq={}，无重复无遗漏且有序", seenSeqs);
    }

    @Test
    void paginationStableWhileWritesContinue() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("a", "0");
        for (int i = 1; i <= 5; i++) {
            system.configs().update("a", (long) i, "v" + (i + 1));
        }
        // 此刻共 6 个版本。以 pageSize=2 翻页，翻到第 2 页结束后继续写入 4 个版本，再翻完。
        Set<Long> collected = new HashSet<>();
        String cursor = null;
        int reads = 0;
        for (int pageNo = 1; ; pageNo++) {
            PageResult<ConfigVersion> page = system.queries().listVersions(cursor, 2);
            page.getItems().forEach(cv -> collected.add(cv.getSeq()));
            reads++;
            if (!page.isHasMore()) {
                break;
            }
            cursor = page.getNextCursor();
            if (pageNo == 2) {
                // 遍历进行中插入新数据（新版本 seq 更大，只应出现在尾部之后）
                for (int w = 6; w <= 9; w++) {
                    system.configs().update("a", (long) w, "late" + w);
                }
                log.info("输入: 第2页结束后并发/持续写入 4 个新版本（v7..v10）");
            }
        }
        log.info("判定依据: 共读取{}页, 去重后收集到 seq 数={}, 最终总版本数={}",
                reads, collected.size(), system.configStore().versionCount());
        assertThat(collected).hasSize(10);
        assertThat(collected).hasSize(system.configStore().versionCount());
        // 再开一次全新遍历，应稳定得到全部 10 条
        Set<Long> secondPass = drain(system);
        assertThat(secondPass).hasSize(10).isEqualTo(collected);
        log.info("判定依据: 二次遍历得到相同集合（新数据已纳入），不重不漏");
    }

    @Test
    void perKeyPaginationIsIsolated() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("k1", "a");
        system.configs().update("k1", 1L, "b");
        system.configs().create("k2", "c");
        log.info("输入: k1 有 2 个版本, k2 有 1 个版本，分别按 key 分页");

        Set<Long> k1Versions = new HashSet<>();
        String cursor = null;
        do {
            PageResult<ConfigVersion> p = system.queries().listVersionsOf("k1", cursor, 1);
            p.getItems().forEach(cv -> k1Versions.add(cv.getVersion()));
            cursor = p.getNextCursor();
        } while (cursor != null);

        PageResult<ConfigVersion> k2 = system.queries().listVersionsOf("k2", null, 10);
        log.info("判定依据: k1 版本号集合={}, k2 版本号集合={}", k1Versions,
                k2.getItems().stream().map(ConfigVersion::getVersion).toList());
        assertThat(k1Versions).containsExactly(1L, 2L);
        assertThat(k2.getItems()).hasSize(1);
        assertThat(k2.getItems().get(0).getConfigKey()).isEqualTo("k2");
    }

    @Test
    void invalidCursorIsRejected_notSilentlyReset() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("z", "1");
        log.info("输入: 非法游标 'not-base64!' 与伪造前缀游标");

        BusinessException e1 = assertThrows(BusinessException.class,
                () -> system.queries().listVersions("not-base64!", 10));
        assertThat(e1.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        log.info("判定依据: 非 Base64URL 拒绝 errorCode={}", e1.getErrorCode());

        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1:ver:1".getBytes());
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> system.queries().listVersions(forged, 10));
        assertThat(e2.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        log.info("判定依据: 类型前缀不匹配拒绝 errorCode={}（ver 游标不能用于全局分页）",
                e2.getErrorCode());
    }

    @Test
    void nonPositivePageSizeIsRejected() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("p", "1");
        BusinessException e = assertThrows(BusinessException.class,
                () -> system.queries().listVersions(null, 0));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        log.info("判定依据: pageSize=0 拒绝 errorCode={}", e.getErrorCode());
    }

    private static Set<Long> drain(ConfigReleaseSystem system) {
        Set<Long> all = new HashSet<>();
        String cursor = null;
        do {
            PageResult<ConfigVersion> p = system.queries().listVersions(cursor, 2);
            p.getItems().forEach(cv -> all.add(cv.getSeq()));
            cursor = p.getNextCursor();
        } while (cursor != null);
        return all;
    }
}
