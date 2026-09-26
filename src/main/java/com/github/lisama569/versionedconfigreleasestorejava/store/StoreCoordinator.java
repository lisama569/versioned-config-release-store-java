package com.github.lisama569.versionedconfigreleasestorejava.store;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 存储层并发协调器：一把全局互斥锁 + 一个全局单调递增序号。
 *
 * <p><b>为什么用一把锁：</b>发布流程同时读写配置版本与发布工单，还需登记幂等键，
 * 若分散加锁极易出现半更新。所有写操作都在 {@link #writeWithLock} 的同一临界区内完成
 * “业务校验 -> 状态迁移 -> 事件落盘 -> 内存态提交”，要么全部成功要么抛出异常、内存态
 * 不变，因此不会留下部分写入。读操作为无锁快照读取（对象不可变或引用整体替换）。
 *
 * <p><b>为什么需要 seq：</b>{@link #nextSeq()} 在锁内分配，严格按提交顺序单调递增，
 * 作为键集分页的最终决胜键；时间戳可能相同，seq 不可能重复。
 */
public final class StoreCoordinator {

    private final ReentrantLock lock = new ReentrantLock();
    private long seq;

    public StoreCoordinator() {
        this(0L);
    }

    /** 恢复时用日志中最大 seq 初始化，保证重启后序号继续单调。 */
    public StoreCoordinator(long initialSeq) {
        if (initialSeq < 0) {
            throw new IllegalArgumentException("initialSeq must be >= 0");
        }
        this.seq = initialSeq;
    }

    /**
     * 恢复用：在写临界区内把序号游标推进到日志最大 seq（只增不减）。
     * 之后 {@link #nextSeq()} 从该值继续，保证重启后仍全局单调。
     */
    public void restoreSeq(long recoveredSeq) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("restoreSeq 只能在 writeWithLock 临界区内调用");
        }
        if (recoveredSeq < this.seq) {
            throw new IllegalArgumentException(
                    "恢复序号不能倒退: current=" + seq + ", recovered=" + recoveredSeq);
        }
        this.seq = recoveredSeq;
    }

    /** 必须在写临界区内调用。 */
    public long nextSeq() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("nextSeq 只能在 writeWithLock 临界区内调用");
        }
        return ++seq;
    }

    /** 当前最大 seq，恢复用。 */
    public long currentSeq() {
        lock.lock();
        try {
            return seq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在互斥临界区内执行一次完整写操作（含落盘副作用）。
     * 若动作抛出异常，临界区正常释放，内存态是否回滚由动作内部自行保证。
     */
    public <T> T writeWithLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 在互斥临界区内执行无返回值的写操作。 */
    public void writeWithLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
