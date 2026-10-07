package com.github.lisama569.versionedconfigreleasestorejava.service;

/** 时间窗维度查询桶。 */
public enum WindowBucket {
    /** 已发布、有到期时刻、尚未回滚（"即将到期"，含已过期未回滚的前段）。 */
    EXPIRING,
    /** 已到期但还没回滚（到期时刻 <= now 且仍 PUBLISHED）。 */
    OVERDUE,
    /** 已被到期扫描自动回滚（rollbackTrigger=AUTO 的终态）。 */
    AUTO_ROLLED_BACK
}
