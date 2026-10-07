# 可持久化配置版本发布系统 — 设计说明

## 1. 总览

本服务在 Spring Boot 3 / JDK 21 上实现一套带版本记录与发布工作流的配置中心内核，
默认使用内嵌 H2 文件库（`./data/configstore`）持久化，无需任何外部服务即可运行与测试。

核心能力：

1. **配置条目**的创建、修改，每次变更在同一事务内追加一条不可变版本记录；
2. **发布工作流** `DRAFT -> PENDING -> PUBLISHED -> ROLLED_BACK`，非法迁移被明确拒绝；
3 发布请求按 `requestId` 幂等，重复提交结果与首次一致、不重复生效；
4. **生效时间窗**：发布单可声明生效起点/终点/时长，窗口到期自动回滚，
   与手动回滚共享同一条 exactly-once 回滚路径；
5. **键集（keyset）分页**遍历条目、版本时间线与时间窗维度列表，持续写入下不重复、不遗漏；
6. 历史数据补充新字段后具有确定、稳定的兼容语义。

包结构：

| 包 | 职责 |
|---|---|
| `domain` | JPA 实体与状态机：`ConfigEntry` / `ConfigRevision` / `Release` / `ReleaseItem` |
| `repo` | Spring Data 仓库，含键集分页 JPQL |
| `service` | `ConfigService` / `ReleaseService` 事务用例，`ReleaseCreationTx` 发布单创建事务边界，`Clock` 时间源 |
| `service.error` | `ConflictReason`（可区分错误码）与 `BusinessException` |
| `support` | `Cursors` 游标编解码、`CursorPage`、`KeyedLocks` 分段锁 |
| `api` | 请求/响应 record（DTO/View） |
| `web` | REST 控制器与全局异常映射 |

---

## 2. 数据模型

建表脚本是确定性的 `src/main/resources/schema.sql`（`CREATE TABLE IF NOT EXISTS`，
`spring.jpa.hibernate.ddl-auto=none`），列名遵循 Spring 的驼峰转下划线策略。

### 2.1 config_entry（配置条目当前态）

| 列 | 说明 |
|---|---|
| `seq` | 数据库 IDENTITY 主键，**全局严格单调递增**，也是分页决胜键 |
| `config_key` | 业务键，唯一（`uk_entry_key`） |
| `config_value` | 当前值（列名避开 SQL 保留字 `value`） |
| `description` | 后补字段，历史行可能为 NULL |
| `category` | 后补字段，DDL 默认 `'DEFAULT'` |
| `status` | `DRAFT` / `ARCHIVED` |
| `value_version` | **业务版本号**，从 1 开始，每次成功修改 +1（与 JPA 乐观锁是两个概念） |
| `format_version` | 行格式代数，新建恒为 2；历史 v1 行由默认值补为 1 |
| `created_at` / `updated_at` | 毫秒精度时间戳（见 §6） |
| `optimistic_version` | JPA `@Version` 乐观锁 |

### 2.2 config_revision（版本记录，append-only）

每次创建/修改/回滚，在**同一事务**内追加一行：

- `change_type`：`CREATE` / `UPDATE` / `RESTORE`（回滚恢复专用，可解释）
- `version_from` / `version_to`：变更前后的业务版本号
- `old_value` / `new_value`、`operator`、`created_at`

由于与条目更新共享事务，不可能出现"条目已更新但缺版本记录"的半更新状态。

### 2.3 release_order（发布单）与 release_item（条目快照）

- `release_order.request_id` 唯一（幂等键）；`state` 为状态机当前态；`@Version` 乐观锁。
- `release_item` 对发布时每个条目冻结两份信息：
  - 创建发布单时：`value_at_create`、`value_version_at_create`；
  - 发布瞬间：`value_version_at_publish`（回滚前置条件的依据）。
- 时间窗相关列（本轮后补，历史行全为 NULL）：
  - `window_start`：声明的生效起点；NULL 表示"自发布起"；
  - `window_end`：生效终点，`state=PUBLISHED 且 window_end <= now` 即到期，
    触发自动回滚；NULL 表示无到期点，**永不参与自动回滚**；
  - `window_duration_seconds`：声明的生效时长。若创建时只给时长，
    终点在发布瞬间结算为 `published_at + 时长`；
  - `rollback_reason`：`MANUAL` / `AUTO_EXPIRED`，未回滚为 NULL。
- 对本轮改动前已存在的库，`schema.sql` 以 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
  幂等补列；新装库直接由 `CREATE TABLE` 建全量列。

---

## 3. 状态机与合法迁移

```
   DRAFT ──submit──▶ PENDING ──publish──▶ PUBLISHED ──rollback──▶ ROLLED_BACK(终态)
```

只有以上三条迁移合法，其余一律抛 `ILLEGAL_STATE_TRANSITION`。迁移合法性由
`Release.canTransitionTo / transitionTo` 集中判定。

**幂等重放边界**（避免"非法操作被静默吞掉"）：

| 操作 | 合法迁移 | 幂等 no-op（返回当前态、无副作用） | 其它状态 |
|---|---|---|---|
| submit | DRAFT→PENDING | 已处于 PENDING | PUBLISHED/ROLLED_BACK → 拒绝 |
| publish | PENDING→PUBLISHED | 已处于 PUBLISHED | DRAFT/ROLLED_BACK → 拒绝 |
| rollback | PUBLISHED→ROLLED_BACK | 已处于 ROLLED_BACK | DRAFT/PENDING → 拒绝 |

回滚语义：把每个条目值恢复为**发布单创建时冻结的值**，并追加一条 `RESTORE` 版本记录。
若发布之后条目又被修改（当前业务版本 ≠ 发布时版本），以 `ROLLBACK_PRECONDITION_FAILED`
拒绝，避免把发布后的新修改静默覆盖。

### 3.1 生效时间窗与自动回滚

**声明方式**（创建发布单时可选，三者均可缺省）：

| 请求字段 | 含义 |
|---|---|
| `effectiveFrom` | 生效起点；缺省表示"自发布起" |
| `effectiveUntil` | 生效终点（绝对时刻），到期自动回滚 |
| `effectiveForSeconds` | 生效时长（秒）。与 `effectiveFrom` 同给时终点在创建即确定；只给时长时终点在发布瞬间结算为 `published_at + 时长` |

校验规则确定且与调用时刻无关：`effectiveUntil` 与 `effectiveForSeconds` 互斥；
时长必须为正；已确定的终点必须晚于起点。违反即 `INVALID_WINDOW`（HTTP 400）。
三个字段全缺省 = 无时间窗，行为与本轮改动前完全一致，永不参与自动回滚。

**到期判定**：`state = PUBLISHED 且 window_end <= now`。`WindowExpirySweeper`
（`@Scheduled`，默认 30s 一轮，可用 `release.window.sweep.enabled=false` 关闭）
每轮取最多 500 张到期发布单逐张自动回滚；也可显式调用
`ReleaseService.processExpiredWindows()`（测试即以此驱动，时序完全确定）。

**自动回滚与手动回滚是同一条路径**：同样恢复为创建时冻结值、同样追加 `RESTORE`
版本记录（operator 分别为 `system-rollback:` / `system-auto-rollback:` 前缀）、
同样受"发布后被改过则拒绝"的前置条件约束。区别仅在于 `rollback_reason`
记为 `AUTO_EXPIRED`，使"已被自动回滚"可查询、可追溯。

**Exactly-once 与并发结论一致**：回滚临界区在独立事务 bean（`ReleaseRollbackTx`）中，
外层（手动 `rollback` / 自动 `rollbackAuto` / 清扫器）不开事务。并发触发时：

1. 乐观锁冲突（状态或条目版本被并发事务推进）→ 外层用**全新事务重试**，
   重试读到获胜方已提交的 `ROLLED_BACK`，按幂等重放返回同一视图；
2. 前置条件检查与并发回滚相撞（读到对方提交前的状态、提交后的条目版本）
   → 同样重试消解；若前置条件失败是真实的（确实被第三方改过），
   重试后仍确定性地抛 `ROLLBACK_PRECONDITION_FAILED`。

因此同一发布单的回滚只真正生效一次（`RESTORE` 只追加一次），无论多少来源、
并发多少次，所有调用得到同一状态与同一 `rollbackReason`。

**边界语义**（均有确定结论）：

- 窗口未到期就手动回滚：正常回滚（`MANUAL`）；到期后的自动触发成为幂等重放，
  返回首次结果，不二次生效；
- 到期后重复触发自动回滚 / 重复清扫：幂等重放，无副作用；
- 窗口未到期时触发自动回滚、或对无窗发布单触发：`WINDOW_NOT_EXPIRED`（HTTP 409）；
- 到点前可 `PUT /api/releases/{id}/window` 整体重设窗口（已发布单只给时长时按
  "当前时刻 + 时长"立即结算新终点）或 `DELETE` 取消；取消后永不自动回滚；
  终态（ROLLED_BACK）上修改窗口以 `ILLEGAL_STATE_TRANSITION` 拒绝；
- 重设的终点若已过去：该单进入"已到期未回滚"，下一轮清扫自动回滚；
- 自动回滚因前置条件失败被拒绝时：该单保持 `PUBLISHED`，留在
  `EXPIRED_PENDING` 查询结果中，后续每轮清扫重试，失败原因落日志。

**时间窗维度分页查询** `GET /api/releases/windows?status=&cursor=&limit=&withinSeconds=`：

| `status` | 口径 |
|---|---|
| `EXPIRING_SOON` | `PUBLISHED` 且 `now < window_end <= now + withinSeconds`（默认 3600s） |
| `EXPIRED_PENDING` | `PUBLISHED` 且 `window_end <= now`（已到期未回滚） |
| `AUTO_ROLLED_BACK` | `ROLLED_BACK` 且 `rollback_reason = AUTO_EXPIRED` |

三种口径的排序键统一为 `(created_at, seq)`（与主列表一致），而不是 `window_end`：
新写入的发布单其排序键必然大于已有游标，持续写入下翻页不重复、不遗漏；
若以 `window_end` 排序，新单可能携带比游标更小的终点而被跳过。
历史无窗发布单（三列全 NULL）不出现在任何一种口径中。

---

## 4. 并发约束与失败原因（可区分）

两层并发控制，错误原因互不混淆：

1. **唯一索引** `uk_entry_key`：并发创建同一 key，只有一个事务成功，
   其余落 `ENTRY_KEY_DUPLICATE`（应用层先做一次可读检查，数据库兜底）。
2. **JPA 乐观锁** `@Version`：并发修改同一行，提交时只有一个生效，
   其余落 `VERSION_CONFLICT`。
3. **业务版本 CAS** `expectedVersion`：调用方基于过期业务版本修改 → `VERSION_CONFLICT`。
4. **值条件** `expectedValue`：以为没人改过值但现值已变 → `VALUE_MISMATCH`。

| `ConflictReason` | HTTP | 触发场景 |
|---|---|---|
| `ENTRY_NOT_FOUND` / `RELEASE_NOT_FOUND` | 404 | 目标不存在 |
| `ENTRY_KEY_DUPLICATE` | 409 | key 唯一冲突 |
| `VERSION_CONFLICT` | 409 | 乐观锁 / 业务版本 CAS 失败 |
| `VALUE_MISMATCH` | 409 | expectedValue 与现值不符 |
| `ILLEGAL_STATE_TRANSITION` | 409 | 非法状态迁移（含并发下状态已被他人推进） |
| `EMPTY_RELEASE` | 409 | 发布单不含任何条目 |
| `ENTRY_NOT_RELEASABLE` | 409 | 条目非 DRAFT，不可纳入发布 |
| `ROLLBACK_PRECONDITION_FAILED` | 409 | 发布后被改过，拒绝回滚覆盖 |
| `IDEMPOTENCY_KEY_MISMATCH` | 409 | 同一 requestId 携带不同请求体（含时间窗字段不一致） |
| `INVALID_WINDOW` | 400 | 时间窗参数非法（互斥字段同给、终点不晚于起点、时长非正） |
| `WINDOW_NOT_EXPIRED` | 409 | 窗口未到期（或无时间窗）时触发自动回滚 |

**原子性**：所有写用例都是单个 `@Transactional` 方法。任何一步抛异常，事务回滚，
不会留下部分写入或半更新；成功提交时条目与其版本记录同时可见。

**创建发布单的锁-事务边界（重点）**：`requestId` 查重 + 插入是典型的 check-then-act。
若锁加在事务方法内部，锁会在事务提交前释放，后来者读不到前者未提交的结果从而重复插入。
因此 `ReleaseService.create` **本身不加事务**，先用 `KeyedLocks`（按 requestId 分段的
进程内 `ReentrantLock`）加锁，再调用独立 bean `ReleaseCreationTx.createInTransaction`
上的 `@Transactional` 方法。锁横跨整个事务：锁释放时事务已经提交，后来者必读到结果，
于是"第一个真正创建、其余命中既有单走幂等重放"，物理上始终只有一行。
数据库唯一索引 `uk_release_request` 作为跨进程/多实例的兜底。
（配置条目的并发创建语义不同——同 key 的第二个创建本就应被拒绝，直接由唯一索引抛
`ENTRY_KEY_DUPLICATE`，无需重放锁。）

**幂等**：创建发布单先按 `requestId` 查；存在且条目集合+备注一致则返回首次结果，
不一致则 `IDEMPOTENCY_KEY_MISMATCH`。状态迁移的重放见 §3。

---

## 5. 分页：不重复、不遗漏

采用键集分页（keyset / seek pagination），不使用会跳数据的 OFFSET。

- **条目列表 / 发布单列表**：排序键 `(created_at, seq)`。`created_at` 为主序，
  `seq` 为同毫秒决胜键且全局唯一，因此排序键全局唯一、单调。
  游标为可读明文 `"<createdAtMillis>_<seq>"`，下一页查询条件是严格大于游标位置。
- **单条目版本时间线**：该条目的版本记录按写入顺序追加，故直接以该条目内的 `seq`
  为游标（不能用全局 `(created_at, seq)`，否则其他更早日条目的记录会串进窗口）。
- 每页多取 1 条（`limit+1`）判定是否还有下一页；最后一页 `nextCursor = null`。

语义保证：排序键单调且游标为"严格上界"。遍历开始后新写入的行其排序键必然更大，
落在所有尚未取到的页之后，因此已遍历行不会重复，且继续翻页必能取到新行，无遗漏。
注意：调用方应从上一页返回的游标继续，而不是自行构造。

---

## 6. 历史数据兼容规则

新增字段时保持确定、稳定、与读取次数无关的语义：

- `description`：历史行物理上为 NULL，**读取时稳定归一化为空串 `""`**；
- `category`：DDL 默认 `'DEFAULT'`；对 NULL 或纯空白也稳定归一化为 `"DEFAULT"`；
  非空值原样保留；
- `format_version`：历史 v1 行由 DDL 默认值补为 1，新写入恒为 2；
  View 中 `max(format_version,1)` 保证读到的代数至少为 1；
- `window_start` / `window_end` / `window_duration_seconds` / `rollback_reason`：
  历史发布单四列物理上为 NULL，稳定含义为"无时间窗、未回滚"——
  永不参与自动回滚，不出现在任何时间窗维度查询中，视图字段原样返回 null；
- 时间戳统一经 `Clock.now()` 截断到**毫秒**：底层 TIMESTAMP 精度有限，
  若写带纳秒的值，读回精度变化会让"重放响应逐字段一致"不稳定。

兼容逻辑集中在 `ConfigService`（`normalizeCategory` 为公开稳定方法），
由 `LegacyCompatibilityTest` 用原生 JDBC 插入"只含 v1 列"的旧行来验证。

---

## 7. REST API 摘要

```
POST   /api/entries                       创建条目（body: key,value,description,category）
PUT    /api/entries/{key}                 修改（可带 expectedVersion / expectedValue）
GET    /api/entries/{key}                 查单条
GET    /api/entries?cursor=&limit=        键集分页
GET    /api/entries/{key}/revisions       该条目版本时间线（seq 游标）

POST   /api/releases                      创建发布单（requestId, entryKeys, remark,
                                        可选 effectiveFrom/effectiveUntil/effectiveForSeconds），幂等
POST   /api/releases/{id}/submit          DRAFT -> PENDING
POST   /api/releases/{id}/publish         PENDING -> PUBLISHED（仅声明时长的窗口在此结算终点）
POST   /api/releases/{id}/rollback        PUBLISHED -> ROLLED_BACK（手动，rollbackReason=MANUAL）
POST   /api/releases/{id}/auto-rollback   到期自动回滚（未到期 -> WINDOW_NOT_EXPIRED）
PUT    /api/releases/{id}/window          到点前整体重设时间窗
DELETE /api/releases/{id}/window          到点前取消时间窗（幂等）
GET    /api/releases/{id}                 查询
GET    /api/releases?cursor=&limit=       键集分页
GET    /api/releases/windows?status=&cursor=&limit=&withinSeconds=
                                        时间窗维度分页（EXPIRING_SOON / EXPIRED_PENDING / AUTO_ROLLED_BACK）
```

错误响应体：`{ "timestamp", "status", "code", "message" }`，`code` 即 §4 的错误码。

---

## 8. 验证方法

### 8.1 全本地、无外部依赖

```bash
mvn -o test                 # 离线跑全部单测/集成测试（H2 内存库，可稳定重复）
```

测试（共 42 个，见 `src/test/.../tests`）：

| 测试类 | 覆盖 |
|---|---|
| `ConfigConcurrencyTest` | 8 线程并发修改仅一个生效，其余 `VERSION_CONFLICT`；`VALUE_MISMATCH` / `ENTRY_KEY_DUPLICATE` / `ENTRY_NOT_FOUND` 可区分；版本记录数与业务版本严格一致（无半更新） |
| `ReleaseLifecycleTest` | 四态全流程；各非法迁移；submit/publish/rollback 重放不重复生效；同 requestId 不同负载被拒；同 requestId 并发提交只落一行且全部返回同一发布单；发布后再改则回滚前置条件失败；空发布单/缺条目 |
| `WindowedReleaseTest` | 窗口到期自动回滚（值恢复 + RESTORE 记录 + `AUTO_EXPIRED`）；窗口未到手动回滚后自动触发为幂等重放；手动/自动 8 线程并发相撞只生效一次且所有调用结论一致；到期后重复触发幂等；未到期/无窗触发自动回滚得 `WINDOW_NOT_EXPIRED`；非法窗口参数得 `INVALID_WINDOW`；到点前重设/取消窗口；终态改窗被拒；终点重设到过去则下轮清扫回滚；同 requestId 不同窗口负载得 `IDEMPOTENCY_KEY_MISMATCH` |
| `WindowQueryTest` | `EXPIRING_SOON` 按 withinSeconds 过滤与精确分页；`EXPIRED_PENDING` 翻页间隙持续写入新到期单不重不漏；`AUTO_ROLLED_BACK` 只含自动回滚；JDBC 插入的无窗历史发布单读取语义稳定、不被清扫、不进任何窗口查询；非法 status/游标拒绝 |
| `PaginationTest` | 同毫秒 seq 决胜；满页/末页边界；畸形游标拒绝；版本时间线页数精确；遍历过程中持续写入不重不漏 |
| `LegacyCompatibilityTest` | JDBC 插入 v1 旧行，验证 DDL 默认值 + 归一化的确定语义 |
| `WebApiTest` | 真实 HTTP：404/409/400 与错误码、重放响应逐字段一致、状态机、回滚后值恢复 |
| `WebWindowApiTest` | 真实 HTTP：带窗创建/重设/取消窗口、窗口维度查询端点、`INVALID_WINDOW`（400）与 `WINDOW_NOT_EXPIRED`（409）可区分 |

测试日志用 SLF4J 打印 **输入**（key、版本、期望值、requestId、entryKeys 等）与
**判定依据/结果**（接受或拒绝原因、页码与游标），例如：

```
updateEntry input: key=k, currentVersion=1, expectedVersion=99, expectedValue=<absent>
business rejection: reason=VERSION_CONFLICT, status=409, message=expected version 99 ...
page decided: inputCursor=..., pageSize=5, returned=5, hasNext=true, nextCursor=...
```

每个测试类用 `@DirtiesContext` + 唯一命名的内存库
（`jdbc:h2:mem:testdb-${random.uuid}`）隔离，互不共享数据。
测试配置中 `release.window.sweep.enabled=false` 关闭后台定时清扫，
时间相关用例以 `Clock.fixedAt(...)` 固定时间源并显式调用
`processExpiredWindows()` 驱动到期处理，不依赖真实等待，离线可重复。

### 8.2 持久化（跨重启）

```bash
mvn -o package -DskipTests
java -jar target/versioned-config-release-store-java-1.0-SNAPSHOT.jar --server.port=8090
# 写入若干条目后 Ctrl+C，再以同样方式重启，GET 数据与版本历史仍在
```

默认文件库位于 `./data/configstore.mv.db`（已在 `.gitignore` 忽略）。
