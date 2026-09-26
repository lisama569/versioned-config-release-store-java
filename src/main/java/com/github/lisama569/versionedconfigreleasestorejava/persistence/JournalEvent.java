package com.github.lisama569.versionedconfigreleasestorejava.persistence;

import java.time.Instant;

/**
 * 仅追加日志中的一条事件。
 *
 * <p>一条事件 = 一次成功的业务状态变更（创建条目/追加版本/建工单/状态迁移）。
 * 采用 Java {@code record}：载荷不可变、字段显式。
 *
 * <p><b>兼容规则：</b>只允许“末尾追加可空/有默认值的字段”，不得改名、改义或删除既有字段。
 * {@code effectivePublishedVersion} 即后增字段：旧日志没有该字段时，Jackson 把缺失的
 * 原始类型字段反序列化为 {@code 0}，语义确定为“无已发布版本”，老数据在新代码下行为稳定。
 *
 * @param type                        事件类型
 * @param seq                         全局单调序号
 * @param at                          业务时间（UTC）
 * @param configKey                   相关配置 key
 * @param version                     相关配置版本号（无则为 0）
 * @param value                       配置内容（版本事件使用，其余为 null）
 * @param orderId                     发布工单 id（工单事件使用，其余为 null）
 * @param statusName                  迁移后的发布状态名（状态迁移事件使用，其余为 null）
 * @param requestId                   触发请求的幂等键（可空）
 * @param actionName                  幂等动作名（可空）
 * @param effectivePublishedVersion   状态迁移后该配置实际生效的已发布版本（后增字段，默认 0）
 */
public record JournalEvent(
        JournalEventType type,
        long seq,
        Instant at,
        String configKey,
        long version,
        String value,
        String orderId,
        String statusName,
        String requestId,
        String actionName,
        long effectivePublishedVersion
) {

    public JournalEvent {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0");
        }
        if (at == null) {
            throw new IllegalArgumentException("at is required");
        }
    }

    /** 不含发布版本信息的构造形式（配置类事件使用，effectivePublishedVersion 取 0）。 */
    public JournalEvent(JournalEventType type,
                        long seq,
                        Instant at,
                        String configKey,
                        long version,
                        String value,
                        String orderId,
                        String statusName,
                        String requestId,
                        String actionName) {
        this(type, seq, at, configKey, version, value, orderId, statusName,
                requestId, actionName, 0L);
    }

    /** 允许以缺省字段构造（为未来新增可空字段预留统一入口）。 */
    public static JournalEvent of(JournalEventType type, long seq, Instant at) {
        return new JournalEvent(type, seq, at, null, 0L, null, null, null, null, null, 0L);
    }

    /**
     * 兼容性归一化：旧版本日志缺少新字段时在此给出确定默认值。
     * 缺失的原始类型字段已由 Jackson 填为 0，无需额外处理；保留入口以固化语义。
     */
    public JournalEvent withDefaults() {
        return this;
    }
}
