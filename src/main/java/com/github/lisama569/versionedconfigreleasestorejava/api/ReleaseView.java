package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.time.Instant;
import java.util.List;

/**
 * 发布单视图。
 *
 * <p>时间窗字段（windowStart / windowEnd / effectiveForSeconds）对历史发布单
 * 恒为 null，含义为"无时间窗，永不参与自动回滚"；rollbackReason 在未回滚时为 null。
 */
public record ReleaseView(long seq, String requestId, String state, List<String> entryKeys,
                          String remark, Long publishedRevisionSeq, int formatVersion,
                          Instant createdAt, Instant updatedAt,
                          Instant windowStart, Instant windowEnd, Long effectiveForSeconds,
                          String rollbackReason) {
}
