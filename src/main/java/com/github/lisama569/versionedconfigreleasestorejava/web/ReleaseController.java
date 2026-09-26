package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.api.CreateReleaseRequest;
import com.github.lisama569.versionedconfigreleasestorejava.api.ReleaseView;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import com.github.lisama569.versionedconfigreleasestorejava.support.CursorPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping("/{requestId}")
    public ReleaseView get(@PathVariable String requestId) {
        return releaseService.get(requestId);
    }

    @GetMapping
    public CursorPage<ReleaseView> list(@RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "50") int limit) {
        return releaseService.list(cursor, limit);
    }
}
