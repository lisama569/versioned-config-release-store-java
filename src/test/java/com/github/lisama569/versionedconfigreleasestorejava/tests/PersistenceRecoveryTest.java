package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.FileEventJournal;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEvent;
import com.github.lisama569.versionedconfigreleasestorejava.persistence.JournalEventType;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 持久化与恢复测试（全部使用临时目录本地文件，无外部服务）：
 * 1) 正常写入 -> 新建系统实例重放 -> 配置版本、工单状态、生效版本、幂等键全部一致；
 * 2) 尾部无换行的半行（模拟崩溃在提交中途）被丢弃，等价于未提交；
 * 3) 完整但无法解析的行 -> PERSISTENCE_ERROR，不静默吞掉；
 * 4) 恢复后序号继续单调，不与历史 seq 冲突。
 */
class PersistenceRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceRecoveryTest.class);

    @Test
    void restartRebuildsIdenticalState(@TempDir Path dir) {
        Path file = dir.resolve("events.log");
        ConfigReleaseSystem first = ConfigReleaseSystem.fileBacked(file);
        first.configs().create("db.pool", "10");
        first.configs().update("db.pool", 1L, "20");
        var order = first.releases().createDraft("db.pool", 2L, "rid-create");
        first.releases().submit(order.orderId(), "rid-submit");
        first.releases().publish(order.orderId(), "rid-publish");
        log.info("输入: 首次系统写入配置2版本并发布 v2, orderId={}", order.orderId());

        // 用同一日志文件重新装配（模拟进程重启）
        ConfigReleaseSystem second = ConfigReleaseSystem.fileBacked(file);
        var entry = second.configs().get("db.pool").orElseThrow();
        var rebuilt = second.releases() == null ? null
                : second.queries().listOrders(null, 10).getItems().get(0);
        log.info("判定依据: 恢复后 当前版本=v{}, 生效版本=v{}, 工单状态={}",
                entry.getCurrentVersion(), entry.getPublishedVersion(), rebuilt.getStatus());
        assertThat(entry.getCurrentVersion()).isEqualTo(2L);
        assertThat(entry.getPublishedVersion()).isEqualTo(2L);
        assertThat(rebuilt.getStatus().name()).isEqualTo("PUBLISHED");

        // 幂等键也随恢复重建：重复请求仍是“重放”，不产生新工单
        var replay = second.releases().submit(order.orderId(), "rid-submit");
        assertThat(replay.replayed()).isTrue();
        var replayCreate = second.releases().createDraft("db.pool", 2L, "rid-create");
        assertThat(replayCreate.replayed()).isTrue();
        assertThat(second.queries().listOrders(null, 10).getItems()).hasSize(1);
        log.info("判定依据: 重启后旧 requestId 仍命中幂等登记，重放不产生新工单");

        // 恢复后继续写入，seq 必须接在历史最大值之后
        var continued = second.configs().update("db.pool", 2L, "30");
        assertThat(continued.version()).isEqualTo(3L);
        long maxSeq = second.queries().listVersions(null, 50).getItems().stream()
                .mapToLong(v -> v.getSeq()).max().orElseThrow();
        log.info("判定依据: 恢复后继续写入得到 v3, 当前最大 seq={}（历史 5 事件 + 本次 1）", maxSeq);
        assertThat(maxSeq).isEqualTo(6L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void trailingPartialLineIsTreatedAsUncommitted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("events.log");
        ObjectMapper mapper = com.github.lisama569.versionedconfigreleasestorejava.support.Json.mapper();
        FileEventJournal journal = new FileEventJournal(file, mapper);
        JournalEvent good = new JournalEvent(JournalEventType.CONFIG_CREATED, 1L,
                Instant.parse("2026-09-26T00:00:00Z"), "k", 1L, "v", null, null, null, null, 0L);
        journal.append(good);

        // 手工追加“半行”：有 JSON 内容但没有结束换行
        String partial = mapper.writeValueAsString(new JournalEvent(JournalEventType.CONFIG_UPDATED,
                2L, Instant.parse("2026-09-26T00:00:01Z"), "k", 2L, "v2",
                null, null, null, null, 0L));
        Files.writeString(file, partial,
                java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.WRITE);
        log.info("输入: 日志含 1 条完整事件 + 1 条无换行半行");

        List<JournalEvent> replayed = journal.replay();
        log.info("判定依据: 重放事件数={}（半行被丢弃，等价于崩溃前未提交）", replayed.size());
        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).type()).isEqualTo(JournalEventType.CONFIG_CREATED);
    }

    @Test
    void corruptCompleteLineRaisesPersistenceError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.log");
        Files.writeString(file, "{this-is-not-json}\n", StandardCharsets.UTF_8);
        ObjectMapper mapper = com.github.lisama569.versionedconfigreleasestorejava.support.Json.mapper();
        FileEventJournal journal = new FileEventJournal(file, mapper);
        log.info("输入: 日志存在一条完整（有换行）但不可解析的行");
        BusinessException ex = assertThrows(BusinessException.class, journal::replay);
        log.info("判定依据: errorCode={}, message={}", ex.getErrorCode(), ex.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERSISTENCE_ERROR);
    }

    @Test
    void gapInSequenceIsRejectedOnRecovery(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("gap.log");
        ObjectMapper mapper = com.github.lisama569.versionedconfigreleasestorejava.support.Json.mapper();
        JournalEvent e1 = new JournalEvent(JournalEventType.CONFIG_CREATED, 1L,
                Instant.parse("2026-09-26T00:00:00Z"), "k", 1L, "v", null, null, null, null, 0L);
        JournalEvent e3 = new JournalEvent(JournalEventType.CONFIG_UPDATED, 3L,
                Instant.parse("2026-09-26T00:00:01Z"), "k", 2L, "v2", null, null, null, null, 0L);
        Files.writeString(file,
                mapper.writeValueAsString(e1) + "\n" + mapper.writeValueAsString(e3) + "\n",
                StandardCharsets.UTF_8);
        log.info("输入: 日志 seq 为 1,3（缺少 2）");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ConfigReleaseSystem.fileBacked(file));
        log.info("判定依据: 恢复拒绝启动 errorCode={}, message={}", ex.getErrorCode(), ex.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERSISTENCE_ERROR);
    }

}

