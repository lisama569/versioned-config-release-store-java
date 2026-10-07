package com.github.lisama569.versionedconfigreleasestorejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 生效时间窗到期清扫器：周期性把"已发布且窗口终点已到"的发布单自动回滚。
 *
 * <p>清扫本身幂等（底层回滚 exactly-once），与手动回滚、其它实例的清扫并发
 * 都安全。默认每 30s 一轮；可用 {@code release.window.sweep.enabled=false}
 * 关闭（测试即如此，改为显式调用 {@link ReleaseService#processExpiredWindows()}
 * 以保证时序确定）。
 */
@Component
@ConditionalOnProperty(prefix = "release.window.sweep", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WindowExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(WindowExpirySweeper.class);

    private final ReleaseService releaseService;

    public WindowExpirySweeper(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @Scheduled(fixedDelayString = "${release.window.sweep.delay-ms:30000}",
            initialDelayString = "${release.window.sweep.initial-delay-ms:10000}")
    public void sweep() {
        try {
            releaseService.processExpiredWindows();
        } catch (RuntimeException e) {
            // 单轮失败不拖垮调度线程，下一轮继续
            log.warn("window sweep round failed: {}", e.getMessage());
        }
    }
}
