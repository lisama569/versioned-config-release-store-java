package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;
import java.util.List;

/**
 * 创建发布单请求。
 *
 * <p>生效时间窗（三个字段均可选，缺省表示无时间窗、永不自动回滚）：
 * <ul>
 *   <li>{@code effectiveFrom}：生效起点；缺省表示"自发布起"；</li>
 *   <li>{@code effectiveUntil}：生效终点，到期自动回滚；</li>
 *   <li>{@code effectiveForSeconds}：生效时长（秒）。若同时给出 effectiveFrom，
 *       终点在创建时即确定为 effectiveFrom + 时长；否则在发布瞬间按发布时间推算。</li>
 * </ul>
 * {@code effectiveUntil} 与 {@code effectiveForSeconds} 互斥。
 */
public record CreateReleaseRequest(String requestId, List<String> entryKeys, String remark,
                                   Instant effectiveFrom, Instant effectiveUntil,
                                   Long effectiveForSeconds) {

    /** 兼容旧调用方：无时间窗的三参构造。 */
    public CreateReleaseRequest(String requestId, List<String> entryKeys, String remark) {
        this(requestId, entryKeys, remark, null, null, null);
    }
}
