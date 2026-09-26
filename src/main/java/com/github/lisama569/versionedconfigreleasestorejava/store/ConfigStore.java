package com.github.lisama569.versionedconfigreleasestorejava.store;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置条目与版本历史存储。
 *
 * <p>内存结构：
 * <ul>
 *   <li>{@code entries}：key -> 当前态 {@link ConfigEntry}；</li>
 *   <li>{@code history}：key -> (版本号 -> 不可变 {@link ConfigVersion})；</li>
 *   <li>{@code bySeq}：全局 seq -> 版本，{@link TreeMap} 保证按提交顺序排序，供分页使用。</li>
 * </ul>
 *
 * <p>所有变更方法都假定调用方已持有 {@link StoreCoordinator} 的写锁（在同一临界区内完成
 * 校验、落盘与提交）。读取方法无锁，依赖不可变对象与 {@code ConcurrentHashMap} 的
 * 整体引用替换语义给出一致快照。
 */
public final class ConfigStore {

    private final Map<String, ConfigEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Long, ConfigVersion>> history = new ConcurrentHashMap<>();
    private final TreeMap<Long, ConfigVersion> bySeq = new TreeMap<>();

    // ---- 写（锁内调用） -------------------------------------------------

    /** 恢复：直接放入条目（日志重放使用，不做业务校验）。 */
    public void restoreEntry(ConfigEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.getConfigKey(), entry);
        history.computeIfAbsent(entry.getConfigKey(), k -> new TreeMap<>());
    }

    /** 恢复：追加一个历史版本并登记 seq 索引。 */
    public void restoreVersion(ConfigVersion v) {
        Objects.requireNonNull(v, "v");
        history.computeIfAbsent(v.getConfigKey(), k -> new TreeMap<>()).put(v.getVersion(), v);
        bySeq.put(v.getSeq(), v);
    }

    /** 新建条目（v1），调用方保证 key 尚不存在。 */
    public ConfigEntry createEntry(String configKey, String value, java.time.Instant at, long seq) {
        ConfigVersion v1 = new ConfigVersion(configKey, 1, value, at, seq);
        ConfigEntry entry = new ConfigEntry(configKey, 1, value, at, at);
        entries.put(configKey, entry);
        TreeMap<Long, ConfigVersion> versions = new TreeMap<>();
        versions.put(1L, v1);
        history.put(configKey, versions);
        bySeq.put(seq, v1);
        return entry;
    }

    /**
     * 追加新版本，调用方已完成存在性与版本冲突校验。
     *
     * @return 新产生的不可变版本
     */
    public ConfigVersion appendVersion(ConfigEntry entry, String value, java.time.Instant at, long seq) {
        long next = entry.getCurrentVersion() + 1;
        ConfigVersion v = new ConfigVersion(entry.getConfigKey(), next, value, at, seq);
        entry.advanceTo(next, value, at); // 同一对象内字段一起切换，外部读到的引用始终一致
        history.get(entry.getConfigKey()).put(next, v);
        bySeq.put(seq, v);
        return v;
    }

    // ---- 读（无锁快照） -------------------------------------------------

    public Optional<ConfigEntry> findEntry(String configKey) {
        return Optional.ofNullable(entries.get(configKey));
    }

    public Optional<ConfigVersion> findVersion(String configKey, long version) {
        TreeMap<Long, ConfigVersion> versions = history.get(configKey);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }

    /** 返回某 key 版本历史的不可变快照（按版本号升序）。 */
    public List<ConfigVersion> versionsOf(String configKey) {
        TreeMap<Long, ConfigVersion> versions = history.get(configKey);
        if (versions == null) {
            return List.of();
        }
        return List.copyOf(versions.values());
    }

    /** 返回 seq 严格大于 {@code afterSeqExclusive} 的版本快照，按 seq 升序，至多 limit 条。 */
    public List<ConfigVersion> versionsAfter(long afterSeqExclusive, int limit) {
        return snapshot(bySeq.tailMap(afterSeqExclusive + 1, true), limit);
    }

    /**
     * 返回某 key 下版本号严格大于 {@code afterVersionExclusive} 的版本快照，
     * 按版本号（即写入顺序）升序，至多 limit 条。
     */
    public List<ConfigVersion> versionsOfAfter(String configKey, long afterVersionExclusive, int limit) {
        TreeMap<Long, ConfigVersion> versions = history.get(configKey);
        if (versions == null) {
            return List.of();
        }
        return snapshot(versions.tailMap(afterVersionExclusive + 1, true), limit);
    }

    private static <K> List<ConfigVersion> snapshot(Map<K, ConfigVersion> tail, int limit) {
        List<ConfigVersion> out = new ArrayList<>(Math.min(limit, tail.size()));
        for (ConfigVersion v : tail.values()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(v);
        }
        return List.copyOf(out);
    }

    /** 版本总数（测试断言与恢复校验用）。 */
    public int versionCount() {
        return bySeq.size();
    }
}
