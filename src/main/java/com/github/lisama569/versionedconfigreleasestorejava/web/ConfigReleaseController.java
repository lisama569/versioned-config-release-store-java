package com.github.lisama569.versionedconfigreleasestorejava.web;

import com.github.lisama569.versionedconfigreleasestorejava.domain.ConfigEntry;
import com.github.lisama569.versionedconfigreleasestorejava.domain.PageResult;
import com.github.lisama569.versionedconfigreleasestorejava.domain.ReleaseOrder;
import com.github.lisama569.versionedconfigreleasestorejava.service.ConfigReleaseSystem;
import com.github.lisama569.versionedconfigreleasestorejava.service.ReleaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置与发布的薄 HTTP 层，只负责参数绑定与结果序列化；全部业务规则在服务层。
 *
 * <ul>
 *   <li>POST /api/configs                    创建配置（{key,value}）</li>
 *   <li>PUT  /api/configs/{key}              基于 expectedVersion 修改</li>
 *   <li>GET  /api/configs/{key}              查当前态</li>
 *   <li>GET  /api/configs/versions           全局版本分页（?cursor&size）</li>
 *   <li>GET  /api/configs/{key}/versions     单 key 版本分页</li>
 *   <li>POST /api/releases                   建发布草稿（{key,version,requestId}）</li>
 *   <li>POST /api/releases/{orderId}/submit    待发布（{requestId}）</li>
 *   <li>POST /api/releases/{orderId}/publish   发布（{requestId}）</li>
 *   <li>POST /api/releases/{orderId}/rollback  回滚（{requestId}）</li>
 *   <li>GET  /api/releases                   工单分页</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ConfigReleaseController {

    private final ConfigReleaseSystem system;

    public ConfigReleaseController(ConfigReleaseSystem system) {
        this.system = system;
    }

    public record CreateConfigRequest(String key, String value) {
    }

    public record UpdateConfigRequest(String value, Long expectedVersion) {
    }

    public record CreateReleaseRequest(String key, Long version, String requestId) {
    }

    public record IdempotentRequest(String requestId) {
    }

    @PostMapping("/configs")
    public Object createConfig(@RequestBody CreateConfigRequest req) {
        var r = system.configs().create(req.key(), req.value());
        return Map.of("configKey", r.configKey(), "version", r.version(), "value", r.value());
    }

    @org.springframework.web.bind.annotation.PutMapping("/configs/{key}")
    public Object updateConfig(@PathVariable String key, @RequestBody UpdateConfigRequest req) {
        var r = system.configs().update(key, req.expectedVersion(), req.value());
        return Map.of("configKey", r.configKey(), "previousVersion", r.previousVersion(),
                "version", r.version(), "value", r.value());
    }

    @GetMapping("/configs/{key}")
    public Map<String, Object> getConfig(@PathVariable String key) {
        ConfigEntry e = system.configs().get(key)
                .orElseThrow(() -> new com.github.lisama569.versionedconfigreleasestorejava.error
                        .BusinessException(com.github.lisama569.versionedconfigreleasestorejava.error
                        .ErrorCode.CONFIG_NOT_FOUND, "配置不存在: key=" + key));
        return toView(e);
    }

    @GetMapping("/configs/versions")
    public PageResult<?> listVersions(@RequestParam(required = false) String cursor,
                                      @RequestParam(defaultValue = "50") int size) {
        return system.queries().listVersions(cursor, size);
    }

    @GetMapping("/configs/{key}/versions")
    public PageResult<?> listVersionsOf(@PathVariable String key,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "50") int size) {
        return system.queries().listVersionsOf(key, cursor, size);
    }

    @PostMapping("/releases")
    public Object createRelease(@RequestBody CreateReleaseRequest req) {
        var r = system.releases().createDraft(req.key(), req.version(), req.requestId());
        return Map.of("orderId", r.orderId(), "configKey", r.configKey(), "version", r.version(),
                "status", r.status().name(), "replayed", r.replayed());
    }

    @PostMapping("/releases/{orderId}/submit")
    public Object submit(@PathVariable String orderId, @RequestBody IdempotentRequest req) {
        return transitionView(system.releases().submit(orderId, req.requestId()));
    }

    @PostMapping("/releases/{orderId}/publish")
    public Object publish(@PathVariable String orderId, @RequestBody IdempotentRequest req) {
        return transitionView(system.releases().publish(orderId, req.requestId()));
    }

    @PostMapping("/releases/{orderId}/rollback")
    public Object rollback(@PathVariable String orderId, @RequestBody IdempotentRequest req) {
        return transitionView(system.releases().rollback(orderId, req.requestId()));
    }

    @GetMapping("/releases")
    public PageResult<ReleaseOrder> listOrders(@RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "50") int size) {
        return system.queries().listOrders(cursor, size);
    }

    private static Map<String, Object> transitionView(ReleaseService.TransitionResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", r.orderId());
        m.put("status", r.status().name());
        m.put("effectivePublishedVersion", r.effectivePublishedVersion());
        m.put("replayed", r.replayed());
        return m;
    }

    private static Map<String, Object> toView(ConfigEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configKey", e.getConfigKey());
        m.put("currentVersion", e.getCurrentVersion());
        m.put("currentValue", e.getCurrentValue());
        m.put("publishedVersion", e.getPublishedVersion());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }
}
