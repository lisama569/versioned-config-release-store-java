package com.github.lisama569.versionedconfigreleasestorejava.support;

/**
 * 键集分页游标编解码。
 *
 * <p>游标为可读的明文格式 {@code "<createdAtEpochMillis>_<seq>"}，
 * 其中 {@code createdAt} 是主键排序时间，{@code seq} 是严格单调递增的写入序号，
 * 用作同一毫秒内多条记录的决胜键，保证排序键 {@code (createdAt, seq)} 全局唯一且有序。
 *
 * <p>明文而非不透明 Base64 是有意为之：排障时可以直接从日志读出翻页位置。
 */
public final class Cursors {

    private Cursors() {
    }

    public static String encode(long createdAtEpochMilli, long seq) {
        return createdAtEpochMilli + "_" + seq;
    }

    /**
     * 解析游标。
     *
     * @return 长度为 2 的数组：[createdAtEpochMillis, seq]
     * @throws IllegalArgumentException 游标格式非法
     */
    public static long[] decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        int sep = cursor.indexOf('_');
        if (sep <= 0 || sep == cursor.length() - 1) {
            throw new IllegalArgumentException("malformed cursor: " + cursor);
        }
        try {
            long millis = Long.parseLong(cursor.substring(0, sep));
            long seq = Long.parseLong(cursor.substring(sep + 1));
            if (millis < 0 || seq < 0) {
                throw new IllegalArgumentException("malformed cursor: " + cursor);
            }
            return new long[] {millis, seq};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed cursor: " + cursor, e);
        }
    }
}
