package com.github.lisama569.versionedconfigreleasestorejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 时间窗相关 REST 端点的端到端测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebWindowApiTest {

    private static final Logger log = LoggerFactory.getLogger(WebWindowApiTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void windowLifecycle_overHttp() {
        String key = "web.win." + System.nanoTime();
        rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "init"), json()), Map.class);
        String requestId = "web-win-" + System.nanoTime();

        // 创建：只生效 3600 秒
        ResponseEntity<Map> created = rest.postForEntity(url("/api/releases"),
                new HttpEntity<>(Map.of("requestId", requestId, "entryKeys", List.of(key),
                        "effectiveForSeconds", 3600), json()), Map.class);
        log.info("HTTP create with window -> status={}, windowEnd={}",
                created.getStatusCode(), created.getBody().get("windowEnd"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertNull(created.getBody().get("windowEnd"), "仅声明时长时发布前终点未结算");
        assertEquals(3600, created.getBody().get("effectiveForSeconds"));

        rest.postForEntity(url("/api/releases/" + requestId + "/submit"), null, Map.class);
        ResponseEntity<Map> published = rest.postForEntity(
                url("/api/releases/" + requestId + "/publish"), null, Map.class);
        assertNotNull(published.getBody().get("windowEnd"), "发布瞬间结算出终点");

        // 重设窗口
        ResponseEntity<Map> updated = rest.exchange(url("/api/releases/" + requestId + "/window"),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("effectiveForSeconds", 7200), json()), Map.class);
        log.info("HTTP update window -> status={}, windowEnd={}",
                updated.getStatusCode(), updated.getBody().get("windowEnd"));
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertEquals(7200, updated.getBody().get("effectiveForSeconds"));

        // 取消窗口
        ResponseEntity<Map> cleared = rest.exchange(url("/api/releases/" + requestId + "/window"),
                HttpMethod.DELETE, null, Map.class);
        assertEquals(HttpStatus.OK, cleared.getStatusCode());
        assertNull(cleared.getBody().get("windowEnd"));

        // 时间窗维度查询端点可用（无窗发布单不在任何口径里）
        ResponseEntity<Map> query = rest.getForEntity(
                url("/api/releases/windows?status=EXPIRED_PENDING&limit=10"), Map.class);
        log.info("HTTP window query -> status={}, items={}",
                query.getStatusCode(), query.getBody().get("items"));
        assertEquals(HttpStatus.OK, query.getStatusCode());
        assertEquals(List.of(), query.getBody().get("items"));
    }

    @Test
    void invalidWindow_overHttp_is400WithDistinctCode() {
        String key = "web.winbad." + System.nanoTime();
        rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "v"), json()), Map.class);
        String requestId = "web-winbad-" + System.nanoTime();

        ResponseEntity<Map> resp = rest.postForEntity(url("/api/releases"),
                new HttpEntity<>(Map.of("requestId", requestId, "entryKeys", List.of(key),
                        "effectiveUntil", "2030-01-01T00:00:00Z",
                        "effectiveForSeconds", 60), json()), Map.class);
        log.info("HTTP invalid window -> status={}, code={}",
                resp.getStatusCode(), resp.getBody().get("code"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("INVALID_WINDOW", resp.getBody().get("code"));
    }

    @Test
    void autoRollbackEndpoint_overHttp_rejectsNotExpiredWithDistinctCode() {
        String key = "web.winauto." + System.nanoTime();
        rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "v"), json()), Map.class);
        String requestId = "web-winauto-" + System.nanoTime();
        rest.postForEntity(url("/api/releases"),
                new HttpEntity<>(Map.of("requestId", requestId, "entryKeys", List.of(key),
                        "effectiveForSeconds", 3600), json()), Map.class);
        rest.postForEntity(url("/api/releases/" + requestId + "/submit"), null, Map.class);
        rest.postForEntity(url("/api/releases/" + requestId + "/publish"), null, Map.class);

        ResponseEntity<Map> resp = rest.postForEntity(
                url("/api/releases/" + requestId + "/auto-rollback"), null, Map.class);
        log.info("HTTP auto-rollback not expired -> status={}, code={}",
                resp.getStatusCode(), resp.getBody().get("code"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("WINDOW_NOT_EXPIRED", resp.getBody().get("code"));
    }
}
