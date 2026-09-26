package com.github.lisama569.versionedconfigreleasestorejava.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯内存日志：用于单测，不依赖磁盘，可稳定重复执行。
 * 同样遵守“整行才可见”的契约（这里即对象整体加入，天然原子）。
 */
public final class InMemoryEventJournal implements EventJournal {

    private final List<JournalEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(JournalEvent event) {
        events.add(event);
    }

    @Override
    public synchronized List<JournalEvent> replay() {
        return List.copyOf(events);
    }

    /** 测试辅助：返回日志条数。 */
    public synchronized int size() {
        return events.size();
    }
}
