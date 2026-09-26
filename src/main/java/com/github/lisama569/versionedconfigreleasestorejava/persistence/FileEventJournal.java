package com.github.lisama569.versionedconfigreleasestorejava.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedconfigreleasestorejava.support.Json;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件型仅追加事件日志：一行一条 JSON，行末 {@code '\n'} 是“提交标记”。
 *
 * <p>崩溃语义：
 * <ul>
 *   <li>{@link #append} 把“JSON + 换行”作为一次写入并 force 刷盘；只有读到结束换行的
 *       行才会在 {@link #replay()} 中被解析；进程在换行前崩溃留下的尾部半行直接丢弃，
 *       等价于该次提交从未发生，不会留下半更新状态。</li>
 *   <li>某行虽完整但 JSON 无法解析（理论上不应发生）视为持久化损坏，抛出
 *       {@code PERSISTENCE_ERROR}，不静默吞掉。</li>
 * </ul>
 * 所有方法 {@code synchronized}，与服务层的写锁共同保证串行追加。
 */
public final class FileEventJournal implements EventJournal {

    private final Path file;
    private final ObjectMapper mapper;

    public FileEventJournal(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = Json.configure(mapper);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                    "无法初始化事件日志: " + file, e);
        }
    }

    @Override
    public synchronized void append(JournalEvent event) {
        try {
            byte[] line = (mapper.writeValueAsString(event) + '\n').getBytes(StandardCharsets.UTF_8);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file.toFile(), true)) {
                fos.write(line);
                fos.flush();
                fos.getChannel().force(true);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                    "追加事件日志失败: seq=" + event.seq() + ", type=" + event.type(), e);
        }
    }

    @Override
    public synchronized List<JournalEvent> replay() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                    "读取事件日志失败: " + file, e);
        }
        List<JournalEvent> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                // [start, i) 是一条以换行结束的完整行
                if (i > start) {
                    out.add(parseLine(new String(bytes, start, i - start, StandardCharsets.UTF_8)));
                }
                start = i + 1;
            }
        }
        // start < bytes.length 表示尾部存在无换行的半行 -> 崩溃未提交，丢弃。
        return List.copyOf(out);
    }

    private JournalEvent parseLine(String json) {
        try {
            return mapper.readValue(json, JournalEvent.class).withDefaults();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PERSISTENCE_ERROR,
                    "事件日志存在无法解析的完整行（数据损坏）: " + file, e);
        }
    }
}
