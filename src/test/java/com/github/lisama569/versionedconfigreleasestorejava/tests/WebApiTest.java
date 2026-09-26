package com.github.lisama569.versionedconfigreleasestorejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端 HTTP 语义测试：错误码可区分、重复提交响应一致、状态迁移通过真实接口生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebApiTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiTest.class);

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
    void errorCodesAreDistinguishable_overHttp() {
        String key = "web." + System.nanoTime();

        // 404: 修改不存在的条目
        ResponseEntity<Map> notFound = rest.exchange(
                url("/api/entries/ghost-web"),
                org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>("{\"value\":\"x\"}", json()),
                Map.class);
        log.info("HTTP PUT missing -> status={}, body={}", notFound.getStatusCode(), notFound.getBody());
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals("ENTRY_NOT_FOUND", notFound.getBody().get("code"));

        // 创建成功
        ResponseEntity<Map> created = rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "v1"), json()), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());

        // 409: 重复键
        ResponseEntity<Map> dup = rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "v2"), json()), Map.class);
        log.info("HTTP duplicate create -> status={}, code={}",
                dup.getStatusCode(), dup.getBody().get("code"));
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());
        assertEquals("ENTRY_KEY_DUPLICATE", dup.getBody().get("code"));

        // 409: 乐观锁版本冲突
        ResponseEntity<Map> conflict = rest.exchange(
                url("/api/entries/" + key),
                org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>("{\"value\":\"v2\",\"expectedVersion\":99}", json()),
                Map.class);
        log.info("HTTP stale version -> status={}, code={}",
                conflict.getStatusCode(), conflict.getBody().get("code"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("VERSION_CONFLICT", conflict.getBody().get("code"));

        // 400: 非法请求
        ResponseEntity<Map> bad = rest.exchange(
                url("/api/entries/" + key),
                org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>("{}", json()),
                Map.class);
        log.info("HTTP null value -> status={}, code={}", bad.getStatusCode(), bad.getBody().get("code"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("INVALID_REQUEST", bad.getBody().get("code"));
    }

    @Test
    void releaseFlowAndReplay_overHttp() {
        String key = "web.rel." + System.nanoTime();
        rest.postForEntity(url("/api/entries"),
                new HttpEntity<>(Map.of("key", key, "value", "init"), json()), Map.class);
        String requestId = "web-req-" + System.nanoTime();

        Map<String, Object> payload = Map.of(
                "requestId", requestId,
                "entryKeys", List.of(key),
                "remark", "http flow");
        ResponseEntity<Map> c1 = rest.postForEntity(url("/api/releases"),
                new HttpEntity<>(payload, json()), Map.class);
        ResponseEntity<Map> c2 = rest.postForEntity(url("/api/releases"),
                new HttpEntity<>(payload, json()), Map.class);
        log.info("HTTP create replay states: {} vs {}", c1.getBody().get("state"), c2.getBody().get("state"));
        assertEquals("DRAFT", c1.getBody().get("state"));
        assertEquals(c1.getBody(), c2.getBody(), "重放结果必须与首次一致");

        ResponseEntity<Map> published = rest.postForEntity(
                url("/api/releases/" + requestId + "/submit"), null, Map.class);
        assertEquals("PENDING", published.getBody().get("state"));
        rest.postForEntity(url("/api/releases/" + requestId + "/publish"), null, Map.class);

        // DRAFT 才能 submit：现在直接再 submit 必须 409 ILLEGAL_STATE_TRANSITION
        ResponseEntity<Map> illegal = rest.postForEntity(
                url("/api/releases/" + requestId + "/submit"), null, Map.class);
        log.info("HTTP submit after publish -> status={}, code={}",
                illegal.getStatusCode(), illegal.getBody().get("code"));
        assertEquals(HttpStatus.CONFLICT, illegal.getStatusCode());
        assertEquals("ILLEGAL_STATE_TRANSITION", illegal.getBody().get("code"));

        // publish 重放：仍 PUBLISHED，不报错
        ResponseEntity<Map> replay = rest.postForEntity(
                url("/api/releases/" + requestId + "/publish"), null, Map.class);
        assertEquals("PUBLISHED", replay.getBody().get("state"));

        ResponseEntity<Map> rolled = rest.postForEntity(
                url("/api/releases/" + requestId + "/rollback"), null, Map.class);
        assertEquals("ROLLED_BACK", rolled.getBody().get("state"));
        // 回滚后值恢复为发布单创建时的值
        ResponseEntity<Map> entry = rest.getForEntity(url("/api/entries/" + key), Map.class);
        assertEquals("init", entry.getBody().get("value"));
        assertNotNull(rolled.getBody().get("updatedAt"));
    }

    @Test
    void paginationTraversal_overHttp() {
        String prefix = "web.page." + System.nanoTime() + ".";
        for (int i = 0; i < 7; i++) {
            rest.postForEntity(url("/api/entries"),
                    new HttpEntity<>(Map.of("key", prefix + i, "value", "v"), json()), Map.class);
        }
        int collected = 0;
        String cursor = null;
        do {
            String path = "/api/entries?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> resp = rest.getForEntity(url(path), Map.class);
            List<?> items = (List<?>) resp.getBody().get("items");
            collected += items.size();
            cursor = (String) resp.getBody().get("nextCursor");
        } while (cursor != null);
        log.info("HTTP traversal collected={} rows (全库，至少包含本用例 7 条)", collected);
        assertEquals(true, collected >= 7);
    }
}
