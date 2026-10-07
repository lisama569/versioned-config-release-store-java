# Versioned Config Release Store（Java / Spring Boot）

带版本记录与发布工作流的可持久化配置存储：并发修改只允许一个生效且冲突原因可区分、
发布状态机合法迁移、请求幂等可重放、键集分页在持续写入下不重不漏，并对历史新字段
有确定的兼容语义。**支持「生效时间窗」**：发布单可指定到点才生效、或只生效一段时间，
窗口结束自动回滚；手动/自动/重复/并发触发对同一发布单只真正回滚一次且结论一致；
可按「即将到期 / 已到期未回滚 / 已自动回滚」分桶分页查询。

- JDK 21 · Spring Boot 3.4 · Spring Data JPA · H2（默认文件库，零外部依赖）
- 详细设计见 [`docs/DESIGN.md`](docs/DESIGN.md)

## 快速开始

```bash
mvn -o spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

数据文件：`./data/configstore.mv.db`。

```bash
# 创建 / 修改（带乐观版本）
curl -s -X POST localhost:8080/api/entries -H 'Content-Type: application/json' \
  -d '{"key":"feature.x","value":"on","category":"payments"}'
curl -s -X PUT  localhost:8080/api/entries/feature.x -H 'Content-Type: application/json' \
  -d '{"value":"off","expectedVersion":1}'

# 发布：DRAFT -> PENDING -> PUBLISHED -> ROLLED_BACK
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"requestId":"r1","entryKeys":["feature.x"]}'
curl -s -X POST localhost:8080/api/releases/r1/submit
curl -s -X POST localhost:8080/api/releases/r1/publish
curl -s -X POST localhost:8080/api/releases/r1/rollback

# 生效时间窗：只在 [生效时刻, 到期时刻] 内有效，到点自动回滚
#   方式一：绝对到期时刻（可再带 effectiveAt 表示到点才生效）
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"requestId":"r2","entryKeys":["feature.x"],"window":{"expireAt":"2026-10-08T12:00:00Z"}}'
#   方式二：相对有效期（发布后 600 秒到期，发布瞬间固化为绝对到期点）
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"requestId":"r3","entryKeys":["feature.x"],"window":{"ttlSeconds":600}}'
# 到点之前改期 / 取消（取消后永不自动回滚）
curl -s -X PUT  localhost:8080/api/releases/r2/window -H 'Content-Type: application/json' \
  -d '{"expireAt":"2026-10-09T00:00:00Z"}'
curl -s -X POST localhost:8080/api/releases/r2/window/cancel

# 按时间窗维度分页（游标已冻结遍历宇宙，持续写入下不重不漏）
curl -s "localhost:8080/api/releases/windows?bucket=EXPIRING&limit=50"
curl -s "localhost:8080/api/releases/windows?bucket=OVERDUE"
curl -s "localhost:8080/api/releases/windows?bucket=AUTO_ROLLED_BACK"

# 分页（键集游标，响应体含 nextCursor）
curl -s "localhost:8080/api/entries?limit=50"
curl -s "localhost:8080/api/entries/feature.x/revisions"
```

到期扫描默认每 1 秒一轮（`release.window.sweep.fixed-delay-ms`，
批大小 `release.window.sweep.batch-size=100`），只负责触发；手动、自动、重复、并发
回滚对同一发布单只真正生效一次，所有调用返回同一个终态与触发来源。
不带 `window` 的发布单行为与以往完全一致，永不参与自动回滚。

## 测试

```bash
mvn -o test        # 44 个用例，H2 内存库，离线、确定性、可重复
```

覆盖并发冲突、重复提交、非法状态迁移、回滚前置条件、分页边界/持续写入、历史数据兼容、
端到端 HTTP 错误码，以及本轮新增的：到期自动回滚（恢复发布前值并留 AUTO 记录）、
手动与自动对撞/重复并发触发只生效一次且结论一致、到点前改期/取消与到点后拒绝、
生效时刻门控与到点自动发布、TTL 固化、无窗口历史数据兼容、时间窗分桶分页边界；
日志打印输入与判定依据。
