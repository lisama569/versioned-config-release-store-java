package com.github.lisama569.versionedconfigreleasestorejava.api;

/**
 * 时间窗维度的发布单查询口径。
 */
public enum WindowQueryStatus {
    /** 已发布、窗口尚未结束，但终点落在未来 withinSeconds 内 */
    EXPIRING_SOON,
    /** 已发布、窗口终点已到，但尚未（自动）回滚 */
    EXPIRED_PENDING,
    /** 窗口到期后已被自动回滚 */
    AUTO_ROLLED_BACK;

    public static WindowQueryStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("status must not be blank, expected one of: "
                    + java.util.Arrays.toString(values()));
        }
        try {
            return WindowQueryStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown window status: " + raw
                    + ", expected one of: " + java.util.Arrays.toString(values()));
        }
    }
}
