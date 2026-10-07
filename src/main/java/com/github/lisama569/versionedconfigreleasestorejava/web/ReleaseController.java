package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.api.WindowRequest;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.service.WindowBucket;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping
    public ReleaseView create(@RequestBody CreateReleaseRequest request) {
        return releaseService.create(request);
    }

    @PostMapping("/{requestId}/submit")
    public ReleaseView submit(@PathVariable String requestId) {
        return releaseService.submit(requestId);
    }

    @PostMapping("/{requestId}/publish")
    public ReleaseView publish(@PathVariable String requestId) {
        return releaseService.publish(requestId);
    }

    @PostMapping("/{requestId}/rollback")
    public ReleaseView rollback(@PathVariable String requestId) {
        return releaseService.rollback(requestId);
    }

    /** 到点之前重新设置生效时间窗（expireAt 与 ttlSeconds 互斥）。 */
    @PutMapping("/{requestId}/window")
    public ReleaseView rescheduleWindow(@PathVariable String requestId,
                                        @RequestBody WindowRequest window) {
        return releaseService.rescheduleWindow(requestId, window);
    }

    /** 到点之前取消时间窗：取消后该发布单永不参与自动回滚。 */
    @PostMapping("/{requestId}/window/cancel")
    public ReleaseView cancelWindow(@PathVariable String requestId) {
        return releaseService.cancelWindow(requestId);
    }

    @GetMapping("/{requestId}")
    public ReleaseView get(@PathVariable String requestId) {
        return releaseService.get(requestId);
    }

    @GetMapping
    public CursorPage<ReleaseView> list(@RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "50") int limit) {
        return releaseService.list(cursor, limit);
    }

    /**
     * 时间窗维度分页：bucket = EXPIRING（即将到期）/ OVERDUE（到期未回滚）
     * / AUTO_ROLLED_BACK（已自动回滚）。
     */
    @GetMapping("/windows")
    public CursorPage<ReleaseView> listWindows(@RequestParam(defaultValue = "EXPIRING") String bucket,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "50") int limit) {
        WindowBucket parsed;
        try {
            parsed = WindowBucket.valueOf(bucket.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid bucket: " + bucket
                            + " (expected EXPIRING / OVERDUE / AUTO_ROLLED_BACK)");
        }
        return releaseService.listWindow(parsed, cursor, limit);
    }
}
