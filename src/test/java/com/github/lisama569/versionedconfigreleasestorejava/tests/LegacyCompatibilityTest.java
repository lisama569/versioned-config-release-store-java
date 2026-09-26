package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.EntryView;
import com.github.lisama569.versionedconfigreleasestorejava.repo.ConfigEntryRepository;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 历史数据兼容测试：
 * v1 时代的行只有 key/value/status/value_version/时间戳，补充新字段
 * (description, category, format_version) 后，对旧行的读取必须有确定语义。
 */

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(LegacyCompatibilityTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigEntryRepository entryRepository;
    @Autowired
    private ConfigService configService;

    @Test
    void legacyRow_withoutNewColumns_isReadWithStableDefaults() {
        String key = "legacy." + System.nanoTime();
        // 模拟 v1 老版本写入：显式只提供 v1 列，新列走 DDL 默认值
        jdbcTemplate.update("""
                INSERT INTO config_entry
                    (config_key, config_value, status, value_version,
                     created_at, updated_at, optimistic_version)
                VALUES (?, ?, 'DRAFT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, key, "legacy-value");

        // 直接走 JDBC 验证落库形态：新列确实是默认值/NULL
        String rawCategory = jdbcTemplate.queryForObject(
                "SELECT category FROM config_entry WHERE config_key = ?", String.class, key);
        Integer rawFormat = jdbcTemplate.queryForObject(
                "SELECT format_version FROM config_entry WHERE config_key = ?", Integer.class, key);
        String rawDescription = jdbcTemplate.queryForObject(
                "SELECT description FROM config_entry WHERE config_key = ?", String.class, key);
        log.info("RAW legacy row: category={}, formatVersion={}, description={}",
                rawCategory, rawFormat, rawDescription);
        assertEquals("DEFAULT", rawCategory, "DDL 默认值必须把旧行 category 补为 DEFAULT");
        assertEquals(1, rawFormat, "旧行格式代数必须为 1");
        assertEquals(null, rawDescription, "旧行 description 物理上为 NULL");

        // 通过服务层读取：归一化语义确定且稳定
        EntryView view = configService.getEntry(key);
        log.info("DECISION normalized: description='{}', category='{}', formatVersion={}",
                view.description(), view.category(), view.formatVersion());
        assertEquals("", view.description(), "NULL description 稳定归一化为空串");
        assertEquals("DEFAULT", view.category(), "NULL/空 category 稳定归一化为 DEFAULT");
        assertEquals(1, view.formatVersion());
        assertEquals("legacy-value", view.value());
        assertEquals(1L, view.version());
        assertNotNull(view.createdAt());
    }

    @Test
    void explicitNullCategory_isAlsoNormalizedToDefault() {
        String key = "legacy.nullcat." + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO config_entry
                    (config_key, config_value, description, category, status, value_version,
                     format_version, created_at, updated_at, optimistic_version)
                VALUES (?, ?, NULL, NULL, 'DRAFT', 1, 2,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, key, "v2-row-null-cat");
        EntryView view = configService.getEntry(key);
        log.info("DECISION explicit-null category normalized to '{}'", view.category());
        assertEquals("", view.description());
        assertEquals("DEFAULT", view.category());
        assertEquals(2, view.formatVersion(), "新格式行保留自身代数，不被错误降级/升级");
    }

    @Test
    void blankCategory_isNormalizedToDefault() {
        ConfigService.normalizeCategory(null);
        assertEquals("DEFAULT", ConfigService.normalizeCategory(null));
        assertEquals("DEFAULT", ConfigService.normalizeCategory("   "));
        assertEquals("payments", ConfigService.normalizeCategory("payments"));
        log.info("DECISION normalizeCategory: null/blank -> DEFAULT, 非空原值保留");
    }
}
