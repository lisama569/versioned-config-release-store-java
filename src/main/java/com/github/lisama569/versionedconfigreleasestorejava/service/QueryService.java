package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigVersion;
import com.github.lisama569.versionedconfigreleasestorejava.domain.PageResult;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseOrder;
import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.store.ConfigStore;
import com.github.lisama569.versionedconfigreleasestorejava.store.ReleaseStore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 查询服务：两类键集（keyset）分页。
 *
 * <p><b>为什么不用 offset：</b>{@code LIMIT n OFFSET k} 在第 1 页与第 2 页之间若有新写入，
 * 会出现条目重复或遗漏。本服务以“上一页最后一条的排序键”为游标，下一页只取排序键严格
 * 更大的行，因此无论期间写入多少数据：
 * <ul>
 *   <li>已访问行的排序键固定不变（seq / 版本号只增且不变序），不会回到后续页面里 -> 不重复；</li>
 *   <li>新写入的行排序键更大，只会出现在“当前尾部之后”，不会顶掉尚未访问的行 -> 不遗漏。</li>
 * </ul>
 *
 * <p>全局分页按 {@code (createdAt, seq)} 排序：同一毫秒内由 seq 决胜；游标编码最后的
 * seq（seq 全局唯一已足够），时间仅作为可读说明。按 key 的分页按 {@code version} 排序，
 * 版本号在 key 内连续递增，语义相同。游标为 Base64URL 文本，格式为 {@code v1:<long>}，
 * 损坏或无法解析时返回 {@code VALIDATION_ERROR}，绝不静默跳到首页。
 */
public final class QueryService {

    /** 单页硬上限，防止调用方一次拉取过多。 */
    public static final int MAX_PAGE_SIZE = 200;

    private static final String PREFIX_SEQ = "v1:seq:";
    private static final String PREFIX_VER = "v1:ver:";

    private final ConfigStore configStore;
    private final ReleaseStore releaseStore;

    public QueryService(ConfigStore configStore, ReleaseStore releaseStore) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.releaseStore = Objects.requireNonNull(releaseStore, "releaseStore");
    }

    // ---- 版本：全局按时间/seq 分页 -------------------------------------

    public PageResult<ConfigVersion> listVersions(String cursor, int pageSize) {
        int size = normalizePageSize(pageSize);
        long afterSeq = cursor == null || cursor.isBlank() ? 0L : decodeLong(cursor, PREFIX_SEQ);
        List<ConfigVersion> rows = configStore.versionsAfter(afterSeq, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        String next = hasMore ? encodeLong(PREFIX_SEQ, rows.get(rows.size() - 1).getSeq()) : null;
        return new PageResult<>(rows, next, hasMore);
    }

    // ---- 版本：按 key + 版本号分页 -------------------------------------

    public PageResult<ConfigVersion> listVersionsOf(String configKey, String cursor, int pageSize) {
        if (configKey == null || configKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "configKey 不能为空");
        }
        int size = normalizePageSize(pageSize);
        long afterVersion = cursor == null || cursor.isBlank()
                ? 0L : decodeLong(cursor, PREFIX_VER);
        List<ConfigVersion> rows = configStore.versionsOfAfter(configKey, afterVersion, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        String next = hasMore ? encodeLong(PREFIX_VER, rows.get(rows.size() - 1).getVersion()) : null;
        return new PageResult<>(rows, next, hasMore);
    }

    // ---- 发布工单：全局按 seq 分页 -------------------------------------

    public PageResult<ReleaseOrder> listOrders(String cursor, int pageSize) {
        int size = normalizePageSize(pageSize);
        long afterSeq = cursor == null || cursor.isBlank() ? 0L : decodeLong(cursor, PREFIX_SEQ);
        List<ReleaseOrder> rows = releaseStore.ordersAfter(afterSeq, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        String next = hasMore ? encodeLong(PREFIX_SEQ, rows.get(rows.size() - 1).getSeq()) : null;
        return new PageResult<>(rows, next, hasMore);
    }

    // ---- 游标编解码 ----------------------------------------------------

    private static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "pageSize 必须为正整数, 输入=" + pageSize);
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static String encodeLong(String prefix, long value) {
        String raw = prefix + value;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeLong(String cursor, String expectedPrefix) {
        String raw;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            raw = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "游标不是合法的 Base64URL: cursor=" + cursor
                            + "；判定依据: 解码失败，拒绝猜测起始位置", e);
        }
        if (!raw.startsWith(expectedPrefix)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "游标类型不匹配: cursor=" + cursor + " 应以 " + expectedPrefix + " 开头");
        }
        try {
            long value = Long.parseLong(raw.substring(expectedPrefix.length()));
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "游标内排序键非法: cursor=" + cursor, e);
        }
    }
}
