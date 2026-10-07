package com.github.lisama569.versionedconfigreleasestorejava.api;

import java.util.List;

public record CreateReleaseRequest(String requestId, List<String> entryKeys, String remark,
                                   WindowRequest window) {

    /** 无时间窗的便捷构造器：行为与历史版本完全一致。 */
    public CreateReleaseRequest(String requestId, List<String> entryKeys, String remark) {
        this(requestId, entryKeys, remark, null);
    }
}
