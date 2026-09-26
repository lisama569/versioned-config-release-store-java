package com.github.lisama569.versionedconfigreleasestorejava.persistence;

import java.util.List;

/**
 * 仅追加事件日志（write-ahead）。
 *
 * <p><b>原子性约定：</b>服务层在同一写临界区内先 {@link #append(JournalEvent)} 成功，
 * 再提交内存态；每条事件是一行完整 JSON（以 {@code '\n'} 结束）。因此：
 * <ul>
 *   <li>日志已落盘而进程在内存提交前崩溃：重启重放会再次应用该事件，得到相同状态；</li>
 *   <li>日志只写了一半（无结束换行）：该行视为从未提交，重放时跳过，不会产生半更新；</li>
 *   <li>业务校验失败时根本不会写日志，自然没有任何副作用。</li>
 * </ul>
 */
public interface EventJournal {

    /** 追加一条完整事件。实现必须保证“要么整行可见、要么不可见”。 */
    void append(JournalEvent event);

    /** 启动恢复：按写入顺序读出全部完整事件（尾部不完整的半行必须被丢弃）。 */
    List<JournalEvent> replay();
}
