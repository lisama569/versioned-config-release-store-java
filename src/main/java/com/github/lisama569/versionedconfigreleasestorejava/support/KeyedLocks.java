package com.github.lisama569.versionedconfigreleasestorejava.support;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按键分段的进程内互斥锁，用于把"查重 -> 插入"这类 check-then-act 临界区串行化。
 *
 * <p>它与数据库唯一约束是纵深防御关系：锁消除同一 JVM 内同键竞态，
 * 唯一约束兜底跨进程/历史数据等锁覆盖不到的情况。锁在临界区结束后若无人竞争即移除，
 * 避免 key 无限累积。
 */
public final class KeyedLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lock(String key) {
        Objects.requireNonNull(key, "key");
        // computeIfAbsize 保证同一 key 拿到同一把锁
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    /** 在锁内执行并返回结果，完成后释放锁。 */
    public <T> T withLock(String key, java.util.function.Supplier<T> action) {
        ReentrantLock lock = lock(key);
        try {
            return action.get();
        } finally {
            lock.unlock();
            // 无等待者时清理，避免 map 无限增长
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }
}
