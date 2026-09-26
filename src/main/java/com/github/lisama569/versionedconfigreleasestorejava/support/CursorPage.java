package com.github.lisama569.versionedconfigreleasestorejava.support;

import java.util.List;

/**
 * 键集分页结果。
 *
 * @param items    当前页元素
 * @param nextCursor 下一页游标；无更多数据时为 null
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
