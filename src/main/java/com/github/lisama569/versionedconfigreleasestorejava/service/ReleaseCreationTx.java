package com.github.lisama569.versionedconfigreleasestorejava.service;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把发布单创建的事务边界单独放在这个 bean 上。
 *
 * <p>必须独立于 {@link ReleaseService}：加锁发生在事务外（见 ReleaseService#create），
 * 而真正的 DB 临界区由本 bean 的 {@code @Transactional} 方法承担。
 * 这样锁的获取/释放横跨整个事务，锁释放时事务已经提交，
 * 后来者一定能读到前者结果，从根本上消除 check-then-act 竞态。
 */
@Component
public class ReleaseCreationTx {

    private final ReleaseService releaseService;

    public ReleaseCreationTx(@Lazy ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @org.springframework.transaction.annotation.Transactional
    public ReleaseView createInTransaction(CreateReleaseRequest request, List<String> normalizedKeys) {
        return releaseService.doCreate(request, normalizedKeys);
    }
}
