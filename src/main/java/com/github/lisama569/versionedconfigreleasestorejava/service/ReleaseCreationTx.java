package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackTrigger;
import com.github.lisama569.versionedconfigreleasestorejava.domain.WindowSpec;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把发布单写操作的事务边界单独放在这个 bean 上。
 *
 * <p>必须独立于 {@link ReleaseService}：加锁发生在事务外（见 ReleaseService 的
 * create/rollback/reschedule），而真正的 DB 临界区由本 bean 的 {@code @Transactional}
 * 方法承担。这样锁的获取/释放横跨整个事务，锁释放时事务已经提交，后来者一定能读到前者
 * 结果，从根本上消除 check-then-act 竞态：
 * <ul>
 *   <li>创建：第一个真正插入，其余命中既有单走幂等重放；</li>
 *   <li>回滚：手动/自动/并发触发中只有一个真正推进状态，其余在事务内读到终态走重放，
 *       全部返回同一个视图。</li>
 * </ul>
 */
@Component
public class ReleaseCreationTx {

    private final ReleaseService releaseService;

    public ReleaseCreationTx(@Lazy ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView createInTransaction(CreateReleaseRequest request, List<String> normalizedKeys,
                                           WindowSpec window) {
        return releaseService.doCreate(request, normalizedKeys, window);
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView rollbackInTransaction(String requestId, RollbackTrigger trigger) {
        return releaseService.doRollback(requestId, trigger);
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView rescheduleInTransaction(String requestId, WindowRequest window) {
        return releaseService.doRescheduleWindow(requestId, window);
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView cancelWindowInTransaction(String requestId) {
        return releaseService.doCancelWindow(requestId);
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView publishInTransaction(String requestId, boolean automatic) {
        return releaseService.doPublishExternal(requestId, automatic);
    }

    /**
     * 并发提交落败后的确定性收敛：在独立新事务中重新读取终态。
     * REQUIRES_NEW 避免读到当前（可能已被标记 rollback-only 的）持久化上下文。
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            readOnly = true)
    public ReleaseView readViewInNewTransaction(String requestId) {
        return releaseService.getFreshView(requestId);
    }
}
