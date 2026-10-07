package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateEntryRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.service.Clock;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseWindowScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时驱动器验证：用毫秒级短间隔调度（覆盖测试默认的超大间隔），证明真实 tick 能驱动
 * 到期回滚，且重复 tick 幂等。固定时钟保证离线、确定性。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "release.window.sweep.fixed-delay-ms=20",
        "release.window.sweep.initial-delay-ms=10",
        "release.window.sweep.batch-size=50"
})
class WindowSchedulerTickTest {

    private static final Logger log = LoggerFactory.getLogger(WindowSchedulerTickTest.class);

    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");

    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ReleaseWindowScheduler scheduler;

    @BeforeEach
    void fixClock() {
        Clock.fixedAt(T0);
    }

    @AfterEach
    void resetClock() {
        Clock.reset();
    }

    @Test
    void scheduledTick_picksUpOnceClockPassesDeadline_andIsIdempotent() throws Exception {
        String key = "sched." + System.nanoTime();
        configService.createEntry(new CreateEntryRequest(key, "base", null, null));
        String req = "sched-req-" + System.nanoTime();
        releaseService.create(new CreateReleaseRequest(req, List.of(key), null,
                new WindowRequest(null, T0.plusSeconds(3600), null)));
        releaseService.submit(req);
        releaseService.publish(req);
        assertEquals("PUBLISHED", releaseService.get(req).state());

        // 时钟越过到期点
        Clock.fixedAt(T0.plusSeconds(3601));

        // 等待真实后台 tick（20ms 间隔）自行完成自动回滚，不做任何手动回滚调用
        long deadline = System.currentTimeMillis() + 5000;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = releaseService.get(req).state();
            if ("ROLLED_BACK".equals(state)) {
                break;
            }
            Thread.sleep(20);
        }
        log.info("DECISION background tick state={}", state);
        assertEquals("ROLLED_BACK", state, "真实定时 tick 必须自动回滚到期发布单");
        assertEquals("AUTO", releaseService.get(req).rollbackTrigger());

        // 再多等几个后台 tick：终态重放，值/版本不被二次影响
        Thread.sleep(120);
        assertEquals("ROLLED_BACK", releaseService.get(req).state());
        assertEquals(2L, configService.getEntry(key).version(), "RESTORE 只发生一次");
        assertTrue(releaseService.sweepExpiredReleases().targets().stream().noneMatch(req::equals));
    }
}
