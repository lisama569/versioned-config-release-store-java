package com.github.lisama569.versionedconfigreleasestorejava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 键集分页（keyset pagination）结果。
 *
 * <p>不使用 offset：offset 分页在数据持续写入时会重复或遗漏。游标 {@code nextCursor}
 * 编码“最后一条记录的排序键”，下一页从该排序键之后继续，因此并发写入下结果稳定。
 * 当 {@code hasMore=false} 时 {@code nextCursor} 为 {@code null}，表示遍历结束。
 */
public final class PageResult<T> {

    private final List<T> items;
    private final String nextCursor;
    private final boolean hasMore;

    public PageResult(List<T> items, String nextCursor, boolean hasMore) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null, false);
    }

    public List<T> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
