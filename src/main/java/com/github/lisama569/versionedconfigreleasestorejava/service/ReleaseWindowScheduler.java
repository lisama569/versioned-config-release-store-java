package com.github.lisama569.versionedconfigreleasestorejava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 生效时间窗的到期驱动器：固定节奏扫描"到点该发布/该回滚"的在途发布单。
 *
 * <p>它只是触发器，真正的判定与生效全部在 {@link ReleaseService} 的
 * 锁+事务路径内完成，因此多次触发、多实例并发、与人工操作相撞都只会产生一次真实生效。
 *
 * <p>节奏可通过 {@code release.window.sweep.fixed-delay-ms} 配置，默认 1000ms；
 * 测试直接调用 service 的 sweep 方法并注入固定时钟，不依赖真实定时，保证离线可重复。
 */
@Component
public class ReleaseWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseWindowScheduler.class);

    private final ReleaseService releaseService;

    @Value("${release.window.sweep.batch-size:100}")
    private int batchSize;

    public ReleaseWindowScheduler(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @Scheduled(fixedDelayString = "${release.window.sweep.fixed-delay-ms:1000}",
            initialDelayString = "${release.window.sweep.initial-delay-ms:500}")
    public void tick() {
        try {
            SweepRequestContext.run(releaseService, batchSize);
        } catch (Exception e) {
            // 单轮失败不影响下一轮；详细原因已在 service 内逐项记录
            log.warn("window sweep tick failed: {}", e.toString());
        }
    }

    /** 一轮内先补到点发布，再处理到期回滚，避免"该发布的没发布却谈到期"。 */
    static final class SweepRequestContext {
        static void run(ReleaseService service, int batchSize) {
            service.sweepEffectiveReleases(batchSize);
            service.sweepExpiredReleases(batchSize);
        }
    }
}
