package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.domain.RollbackReason;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 回滚的事务边界，与 {@link ReleaseCreationTx} 同理独立于
 * {@link ReleaseService}：外层包装方法（手动/自动回滚）本身不开事务，
 * 在乐观锁冲突时可以用一个全新的事务重试——重试时另一个并发回滚已经提交，
 * 本调用即按"已回滚"幂等重放，从而所有并发触发者得到同一状态、同一结论。
 */
@Component
public class ReleaseRollbackTx {

    private final ReleaseService releaseService;

    public ReleaseRollbackTx(@Lazy ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView rollbackInTransaction(String requestId, RollbackReason reason) {
        return releaseService.doRollback(requestId, reason);
    }
}
