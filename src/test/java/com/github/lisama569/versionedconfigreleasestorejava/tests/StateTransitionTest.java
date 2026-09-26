package com.github.lisama569.versionedconfigreleasestorejava.tests;

import com.github.lisama569.versionedconfigreleasestorejava.error.BusinessException;
import com.github.lisama569.versionedconfigreleasestorejava.error.ErrorCode;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 发布状态机测试：
 * 合法链 DRAFT->PENDING->PUBLISHED->ROLLED_BACK、PENDING->ROLLED_BACK 撤销；
 * 非法迁移（DRAFT 直接发布/回滚、重复发布、回滚后再迁移）一律 ILLEGAL_TRANSITION；
 * 资源缺失分别得到 RELEASE_NOT_FOUND / VERSION_NOT_FOUND / CONFIG_NOT_FOUND，原因可区分。
 */
class StateTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(StateTransitionTest.class);

    private ConfigReleaseSystem seededWithThreeVersions() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("app", "v1");
        system.configs().update("app", 1L, "v2");
        system.configs().update("app", 2L, "v3");
        return system;
    }

    @Test
    void legalChainAndIllegalTransitions() {
        ConfigReleaseSystem system = seededWithThreeVersions();
        var draft = system.releases().createDraft("app", 3L, "c");
        log.info("输入: 新建工单 orderId={} (key=app,v3)，初始状态=DRAFT", draft.orderId());

        // DRAFT 直接发布 -> 非法
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> system.releases().publish(draft.orderId(), "p-early"));
        assertThat(e1.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        log.info("判定依据: DRAFT->publish 拒绝 errorCode={}, message={}",
                e1.getErrorCode(), e1.getMessage());

        // DRAFT 直接回滚 -> 非法
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> system.releases().rollback(draft.orderId(), "r-early"));
        assertThat(e2.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        log.info("判定依据: DRAFT->rollback 拒绝 errorCode={}", e2.getErrorCode());

        // 合法链
        assertThat(system.releases().submit(draft.orderId(), "s").status().name())
                .isEqualTo("PENDING");
        var pub = system.releases().publish(draft.orderId(), "p");
        assertThat(pub.status().name()).isEqualTo("PUBLISHED");
        assertThat(pub.effectivePublishedVersion()).isEqualTo(3L);
        log.info("判定依据: 合法迁移完成 PENDING->PUBLISHED, 生效=v{}", pub.effectivePublishedVersion());

        // 重复发布 -> 非法（同 requestId 重放才被允许，但那不是“再次生效”；换新 requestId 就是非法迁移）
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> system.releases().publish(draft.orderId(), "p-again"));
        assertThat(e3.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        log.info("判定依据: PUBLISHED->publish 拒绝 errorCode={}", e3.getErrorCode());

        var rb = system.releases().rollback(draft.orderId(), "r");
        assertThat(rb.status().name()).isEqualTo("ROLLED_BACK");
        log.info("判定依据: PUBLISHED->ROLLED_BACK, 回滚后生效版本=v{}",
                rb.effectivePublishedVersion());

        // 终态再迁移 -> 非法
        BusinessException e4 = assertThrows(BusinessException.class,
                () -> system.releases().publish(draft.orderId(), "p-dead"));
        assertThat(e4.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        BusinessException e5 = assertThrows(BusinessException.class,
                () -> system.releases().rollback(draft.orderId(), "r-dead"));
        assertThat(e5.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
        log.info("判定依据: ROLLED_BACK 终态再次 publish/rollback 均拒绝 errorCode={}",
                e5.getErrorCode());
    }

    @Test
    void pendingRollbackIsCancellation() {
        ConfigReleaseSystem system = seededWithThreeVersions();
        var draft = system.releases().createDraft("app", 2L, "c");
        system.releases().submit(draft.orderId(), "s");
        var rb = system.releases().rollback(draft.orderId(), "r");
        log.info("输入: PENDING 状态直接回滚（撤销未生效发布）, 结果={}, 生效版本=v{}",
                rb.status(), rb.effectivePublishedVersion());
        assertThat(rb.status().name()).isEqualTo("ROLLED_BACK");
        assertThat(rb.effectivePublishedVersion()).isZero();
    }

    @Test
    void rollbackRestoresPreviousPublishedVersion() {
        ConfigReleaseSystem system = seededWithThreeVersions(); // v1,v2,v3
        // 先发布 v1
        var o1 = system.releases().createDraft("app", 1L, "c1");
        system.releases().submit(o1.orderId(), "s1");
        system.releases().publish(o1.orderId(), "p1");
        // 再发布 v2
        var o2 = system.releases().createDraft("app", 2L, "c2");
        system.releases().submit(o2.orderId(), "s2");
        var p2 = system.releases().publish(o2.orderId(), "p2");
        log.info("输入: 依次发布 v1、v2，当前生效=v{}", p2.effectivePublishedVersion());
        assertThat(p2.effectivePublishedVersion()).isEqualTo(2L);

        // 回滚 v2 工单 -> 生效版本回退到 v1（上一个仍 PUBLISHED 的最高版本）
        var rb = system.releases().rollback(o2.orderId(), "r2");
        log.info("判定依据: 回滚 v2 工单后实际生效=v{}（期望回到 v1）",
                rb.effectivePublishedVersion());
        assertThat(rb.effectivePublishedVersion()).isEqualTo(1L);
        assertThat(system.configs().get("app").orElseThrow().getPublishedVersion()).isEqualTo(1L);

        // 再回滚 v1 -> 没有任何已发布，生效版本归零
        var rb2 = system.releases().rollback(o1.orderId(), "r1");
        log.info("判定依据: 再回滚 v1 工单后实际生效=v{}（期望 0=从未发布）",
                rb2.effectivePublishedVersion());
        assertThat(rb2.effectivePublishedVersion()).isZero();
    }

    @Test
    void publishingNonForwardVersionIsRejected() {
        ConfigReleaseSystem system = seededWithThreeVersions();
        var o2 = system.releases().createDraft("app", 2L, "c2");
        system.releases().submit(o2.orderId(), "s2");
        system.releases().publish(o2.orderId(), "p2"); // 生效 v2

        var o1 = system.releases().createDraft("app", 1L, "c1");
        system.releases().submit(o1.orderId(), "s1");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> system.releases().publish(o1.orderId(), "p1"));
        log.info("判定依据: 已生效 v2 后发布更早的 v1 被拒绝 errorCode={}, message={}",
                ex.getErrorCode(), ex.getMessage());
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION);
    }

    @Test
    void missingResourcesGiveDistinctErrorCodes() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        system.configs().create("only", "x");

        BusinessException order = assertThrows(BusinessException.class,
                () -> system.releases().submit("D-missing", "r"));
        assertThat(order.getErrorCode()).isEqualTo(ErrorCode.RELEASE_NOT_FOUND);

        BusinessException version = assertThrows(BusinessException.class,
                () -> system.releases().createDraft("only", 99L, "r"));
        assertThat(version.getErrorCode()).isEqualTo(ErrorCode.VERSION_NOT_FOUND);

        BusinessException config = assertThrows(BusinessException.class,
                () -> system.releases().createDraft("ghost", 1L, "r"));
        assertThat(config.getErrorCode()).isEqualTo(ErrorCode.CONFIG_NOT_FOUND);
        log.info("判定依据: 三种缺失分别为 RELEASE_NOT_FOUND / VERSION_NOT_FOUND / CONFIG_NOT_FOUND: {},{},{}",
                order.getErrorCode(), version.getErrorCode(), config.getErrorCode());
    }

    @Test
    void validationErrorsAreDistinct() {
        ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
        BusinessException noRequest = assertThrows(BusinessException.class,
                () -> system.releases().submit("D-1", "  "));
        assertThat(noRequest.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);

        BusinessException badVersion = assertThrows(BusinessException.class,
                () -> system.releases().createDraft("k", 0L, "r"));
        assertThat(badVersion.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        log.info("判定依据: 空 requestId / 非法版本号均为 VALIDATION_ERROR（与业务冲突区分）");
    }
}
