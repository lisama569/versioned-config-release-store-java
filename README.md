# Versioned Config Release Store（Java / Spring Boot）

带版本记录与发布工作流的可持久化配置存储：并发修改只允许一个生效且冲突原因可区分、
发布状态机合法迁移、请求幂等可重放、键集分页在持续写入下不重不漏，并对历史新字段
有确定的兼容语义。

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

# 生效时间窗：只生效 1 小时，到期自动回滚（等效于手动回滚，rollbackReason=AUTO_EXPIRED）
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -d '{"requestId":"r2","entryKeys":["feature.x"],"effectiveForSeconds":3600}'
# 到点前可重设 / 取消时间窗
curl -s -X PUT    localhost:8080/api/releases/r2/window -H 'Content-Type: application/json' \
  -d '{"effectiveForSeconds":7200}'
curl -s -X DELETE localhost:8080/api/releases/r2/window
# 按时间窗维度分页查询：EXPIRING_SOON / EXPIRED_PENDING / AUTO_ROLLED_BACK
curl -s "localhost:8080/api/releases/windows?status=EXPIRED_PENDING&limit=50"

# 分页（键集游标，响应体含 nextCursor）
curl -s "localhost:8080/api/entries?limit=50"
curl -s "localhost:8080/api/entries/feature.x/revisions"
```

## 测试

```bash
mvn -o test        # 42 个用例，H2 内存库，离线、确定性、可重复
```

覆盖并发冲突、重复提交、非法状态迁移、回滚前置条件、时间窗到期自动回滚、
手动/自动回滚并发相撞只生效一次、时间窗分页边界/持续写入、历史数据兼容、
端到端 HTTP 错误码；日志打印输入与判定依据。
