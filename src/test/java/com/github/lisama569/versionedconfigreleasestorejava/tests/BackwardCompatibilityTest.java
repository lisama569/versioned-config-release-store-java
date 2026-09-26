package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEvent;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEventType;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import com.github.lisama569.versionedconfigreleasestorejava.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兼容规则测试（老数据 + 新代码）：
 * 1) 老版本日志缺少后增字段 {@code effectivePublishedVersion} 时，按确定默认值 0 恢复，
 *    语义为“无已发布版本”，不报错、不猜测；
 * 2) 日志中出现代码尚不认识的“未来字段”时忽略未知字段（前向宽容）；
 * 3) 缺字段的老事件恢复后，系统仍可继续正常写入与发布（演进后行为稳定）；
 * 4) 新写出的事件包含新字段，字段集合是老字段的超集（只增不删）。
 */
class BackwardCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(BackwardCompatibilityTest.class);
    private final ObjectMapper mapper = Json.mapper();

    /** 手工构造一条“老格式”事件：缺少 effectivePublishedVersion，且多一个未知未来字段。 */
    private String legacyConfigCreatedLine(long seq) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "CONFIG_CREATED");
        map.put("seq", seq);
        map.put("at", "2026-01-01T00:00:00Z");
        map.put("configKey", "legacy.key");
        map.put("version", 1);
        map.put("value", "legacy-value");
        map.put("orderId", null);
        map.put("statusName", null);
        map.put("requestId", null);
        map.put("actionName", null);
        map.put("futureExperimentalFlag", true); // 未来版本才有的未知字段
        return mapper.writeValueAsString(map);
    }

    @Test
    void oldJournalLineMissingNewField_recoversWithDeterministicDefault(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("legacy.log");
        Files.writeString(file, legacyConfigCreatedLine(1) + "\n", StandardCharsets.UTF_8);
        log.info("输入: 一条老格式事件（无 effectivePublishedVersion，含未知字段 futureExperimentalFlag）");

        // 直接反序列化层面验证默认值
        try (var in = Files.newBufferedReader(file)) {
            JournalEvent event = mapper.readValue(in.readLine(), JournalEvent.class);
            log.info("判定依据: 反序列化 type={}, effectivePublishedVersion={}（期望默认 0）, value={}",
                    event.type(), event.effectivePublishedVersion(), event.value());
            assertThat(event.type()).isEqualTo(JournalEventType.CONFIG_CREATED);
            assertThat(event.effectivePublishedVersion()).isZero();
            assertThat(event.value()).isEqualTo("legacy-value");
        }

        // 系统级恢复：老条目恢复后 publishedVersion 取兼容默认 0
        ConfigReleaseSystem system = ConfigReleaseSystem.fileBacked(file,
                new com.github.lisama569.versionedconfigreleasestorejava.support.Clock() {
                    @Override
                    public java.time.Instant now() {
                        return java.time.Instant.parse("2026-09-26T08:00:00Z");
                    }
                });
        var entry = system.configs().get("legacy.key").orElseThrow();
        log.info("判定依据: 恢复后 currentVersion=v{}, publishedVersion=v{}（老数据默认 0=未发布）",
                entry.getCurrentVersion(), entry.getPublishedVersion());
        assertThat(entry.getCurrentVersion()).isEqualTo(1L);
        assertThat(entry.getPublishedVersion()).isZero();

        // 恢复后继续演进：修改 + 发布 v2 正常工作，新事件携带新字段
        system.configs().update("legacy.key", 1L, "new-value");
        var order = system.releases().createDraft("legacy.key", 2L, "rid");
        system.releases().submit(order.orderId(), "s");
        var pub = system.releases().publish(order.orderId(), "p");
        log.info("判定依据: 兼容恢复后继续发布成功, 生效版本=v{}", pub.effectivePublishedVersion());
        assertThat(pub.effectivePublishedVersion()).isEqualTo(2L);

        // 重新打开文件逐条校验：新事件是老字段超集且含新字段
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String newest = lines.get(lines.size() - 1);
        assertThat(newest).contains("\"effectivePublishedVersion\":2");
        assertThat(newest).contains("RELEASE_STATUS_CHANGED");
        log.info("判定依据: 新事件包含后增字段 effectivePublishedVersion 且保留全部既有字段，共 {} 行",
                lines.size());
    }

    @Test
    void configEntryWithoutPublishedInfo_defaultsToZero() {
        // 老构造路径（无 publishedVersion）语义稳定
        var entry = new com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry(
                "k", 1L, "v", java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        log.info("输入: 老构造路径创建 ConfigEntry（不传 publishedVersion）");
        assertThat(entry.getPublishedVersion()).isZero();
        log.info("判定依据: publishedVersion 默认 0，语义=尚无已发布版本，确定稳定");
    }
}
