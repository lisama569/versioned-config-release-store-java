# 可持久化配置版本发布系统 — 设计说明

## 1. 总览

本服务在 Spring Boot 3 / JDK 21 上实现一套带版本记录与发布工作流的配置中心内核，
默认使用内嵌 H2 文件库（`./data/configstore`）持久化，无需任何外部服务即可运行与测试。

核心能力：

1. **配置条目**的创建、修改，每次变更在同一事务内追加一条不可变版本记录；
2. **发布工作流** `DRAFT -> PENDING -> PUBLISHED -> ROLLED_BACK`，非法迁移被明确拒绝；
3 发布请求按 `requestId` 幂等，重复提交结果与首次一致、不重复生效；
4. **键集（keyset）分页**遍历条目与版本时间线，持续写入下不重复、不遗漏；
5. 历史数据补充新字段后具有确定、稳定的兼容语义；
6. **生效时间窗 + 到期自动回滚**：发布单可携带生效时刻与到期定义（绝对时刻或相对
   TTL），到点由扫描器自动发布/自动回滚；手动、自动、重复与并发触发对同一发布单
   只会真正回滚一次，所有调用得到同一个终态与结论（见 §3.1、§4.1）。

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
- 生效时间窗相关列（本轮新增，历史行全部为 NULL，语义见 §6）：
  - `effective_at`：生效时刻。非空时，到达该时刻才允许人工/自动发布；
  - `expire_at`：绝对到期时刻。到点自动回滚；TTL 窗口在发布瞬间把
    `published_at + ttl_seconds` 固化写入此列，此后改期不再改变该到期点；
  - `ttl_seconds`：相对有效期（秒，正数）。与 `expire_at` 在请求中互斥；
  - `rollback_trigger`：回滚触发来源 `MANUAL` / `AUTO`。未回滚为 NULL；
    历史上（本特性前）回滚的行该列也为 NULL，读取稳定归一化为 `MANUAL`。
- 到期/生效扫描索引：`idx_release_due (expire_at, seq)`、
  `idx_release_effective (effective_at, seq)`，在途发布单很多时扫描仍是有界范围查询。

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

### 3.1 生效时间窗语义（本轮新增）

创建发布单时可在 `window` 中三选其一定义窗口（也可不带，行为与历史完全一致）：

| 字段 | 含义 | 约束 |
|---|---|---|
| `effectiveAt` | 生效时刻（绝对） | 可单独使用；若与 `expireAt` 同给，必须早于它 |
| `expireAt` | 绝对到期时刻 | 必须晚于创建当前时刻；与 `ttlSeconds` 互斥 |
| `ttlSeconds` | 相对有效期（秒） | 必须为正；发布瞬间固化为 `publishedAt + ttl` 的绝对到期点 |

非法组合统一落 `INVALID_WINDOW`（互斥冲突、TTL 非正、到期在过去、生效晚于到期）。

**状态与时间上的约束：**

- **生效门控**：`effectiveAt` 未到，人工 `publish` 落 `WINDOW_NOT_EFFECTIVE`；
  到达后由扫描器自动发布（也允许人工发布）。
- **未发布即过期**：PENDING/DRAFT 的发布单在发布前窗口就已结束
  （`expireAt <= now`），人工发布落 `WINDOW_EXPIRED`，该发布永不生效。
- **到期自动回滚**：PUBLISHED 且 `expireAt <= now` 的发布单由扫描器自动回滚，
  走与手动回滚**完全相同**的恢复与留痕逻辑（恢复创建时冻结值 + 一条
  `RESTORE` 记录，`operator=system-auto-rollback:<requestId>`，
  `rollback_trigger=AUTO`）。
- **到点之前可改期/取消**：`PUT /api/releases/{id}/window` 重新设置窗口，
  `POST /api/releases/{id}/window/cancel` 取消；取消后该发布单与"从未设置窗口"
  完全一致，永不参与自动回滚。
- **到点之后不可改期/取消**：已到期但尚未回滚的 PUBLISHED 发布单，改期/取消落
  `WINDOW_EXPIRED`——到期是确定事件，只能回滚，不能靠改时间点绕开。
  ROLLED_BACK 终态上改期/取消落 `ILLEGAL_STATE_TRANSITION`。
- **TTL 固化**：TTL 窗口一旦发布，到期时刻即固化为绝对时间；之后改期必须显式
  提供新定义，旧的 TTL 不会"重新计时"已确定的到期点。

**自动 vs 手动回滚的唯一区别是来源标记，结论完全一致**：二者恢复的值、追加的
版本记录结构、终态相同；`rollback_trigger` 记录"是谁触发的"，便于追溯与解释。

### 3.2 到期驱动器

`ReleaseWindowScheduler` 以可配置节奏（`release.window.sweep.fixed-delay-ms`，
默认 1000ms；批大小 `...batch-size` 默认 100）执行：先扫"到点该发布"
（PENDING 且 `effectiveAt <= now`），再扫"到点该回滚"（PUBLISHED 且
`expireAt <= now`），逐单在**独立事务 + 按 requestId 分段锁**内处理。
调度器只是触发器；是否生效由 service 的统一判定决定，因此重复扫描、多实例并发、
与人工操作相撞都安全。单批有界，配合到期索引，在途发布单很多时扫描开销稳定，
不需要人为构造特殊时间点。

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
| `IDEMPOTENCY_KEY_MISMATCH` | 409 | 同一 requestId 携带不同请求体 |
| `INVALID_WINDOW` | 409 | 时间窗参数非法（互斥/非正/过期/顺序错） |
| `WINDOW_NOT_EFFECTIVE` | 409 | 未到生效时刻，拒绝人工发布 |
| `WINDOW_EXPIRED` | 409 | 窗口已到期：未发布即过期拒绝发布，或到期后拒绝改期/取消 |

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

**幂等**：创建发布单先按 `requestId` 查；存在且条目集合+备注+时间窗一致则返回首次结果，
不一致则 `IDEMPOTENCY_KEY_MISMATCH`。状态迁移的重放见 §3。

### 4.1 回滚"只真正发生一次"的确定性（手动/自动/重复/并发对撞）

同一张发布单可能同时被：人工 rollback、到期扫描、重复请求、多个来源并发触发。
无论谁在什么时候触发多少次，结果必须是**一个终态、一个明确结论**，不允许一部分成功、
另一部分得到含糊的冲突回答。实现上三层保证：

1. **进程内分段锁横跨事务**：`rollback/autoRollback` 本身不持事务，先按 requestId 取
   `KeyedLocks`，再调用 `ReleaseCreationTx` 上的 `@Transactional` 方法（与创建同一
   锁-事务边界模式）。锁释放时事务已提交，同一 JVM 内对同一发布单的所有触发被
   严格串行化：
   - 第一个进入者读到 PUBLISHED，执行恢复 + 追加 `RESTORE` + 置 ROLLED_BACK 并标记
     触发来源，唯一一次真正生效；
   - 后来者（无论手动还是自动）在事务内读到的已是 ROLLED_BACK，统一走**重放**：
     不做第二次恢复，直接返回同一个视图（同一个 seq、同一个状态、同一个触发来源）。
2. **JPA `@Version` 乐观锁兜底跨进程/多实例**：极端情况下两个事务同时推进，提交时
   落败者抛 `ObjectOptimisticLockingFailureException`，其事务回滚；该异常在**事务
   边界外**被捕获，随后在一个独立新事务（`REQUIRES_NEW`）重读终态并返回。于是落败
   调用拿到的也是"已回滚 + 首个生效者的来源"这同一结论，而不是 409。
3. **恢复与留痕同事务**：值恢复与 `RESTORE` 版本记录、状态推进在同一个事务内提交，
   不可能出现"回滚了但没有记录"或反之。

注意：回滚触发来源（`rollback_trigger`）由**第一个真正生效者**决定并随终态固化；
后来的任何重放都只读到它。手动早于自动则终态是 `MANUAL`，自动先到则是 `AUTO`，
但对所有调用方呈现的都是同一个值。`ROLLBACK_PRECONDITION_FAILED`（发布后被人改过）
仍是显式拒绝：该单保留 PUBLISHED，扫描器会持续记录失败并留待人工介入，不会静默覆盖。

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

### 5.1 按时间窗维度分页（冻结宇宙，不重不漏）

`GET /api/releases/windows?bucket=...` 支持三个桶：

| bucket | 含义 | 成员判定 | 排序键 |
|---|---|---|---|
| `EXPIRING` | 即将到期（在途） | state=PUBLISHED 且 expireAt 非空 | `(expireAt, seq)` |
| `OVERDUE` | 已到期但还没回滚 | state=PUBLISHED 且 expireAt <= now | `(expireAt, seq)` |
| `AUTO_ROLLED_BACK` | 已被自动回滚 | state=ROLLED_BACK 且 trigger=AUTO | `seq` |

普通 `(createdAt,seq)` 键集分页面对一个特殊困难：时间窗列表的"成员资格"会随时间
变化（到期、回滚都会让行进/出集合），且在途集合持续有新发布写入。为在"持续有新
发布写入"下翻页不重不漏，首页除返回数据外，还取一次 `max(seq)` 作为**本次遍历的
创建 seq 上界（冻结宇宙）**，并把它编进游标：`"<orderMillis>_<seq>_<maxSeq>"`。
后续每页都带 `seq <= maxSeq` 谓词：

- 遍历开始后才创建的发布单（seq > maxSeq）本次一律不可见，不会插到前页导致重复/
  遗漏，下一轮全新遍历（新的 maxSeq）才纳入；
- 排序键唯一（到期时刻同值时由全局唯一 seq 决胜），游标是严格上界，配合冻结上界，
  已取行不再出现、应取行不会丢失；
- `AUTO_ROLLED_BACK` 是终态集合，直接以不可变的创建 `seq` 排序即可。

---

## 6. 历史数据兼容规则

新增字段时保持确定、稳定、与读取次数无关的语义：

- `description`：历史行物理上为 NULL，**读取时稳定归一化为空串 `""`**；
- `category`：DDL 默认 `'DEFAULT'`；对 NULL 或纯空白也稳定归一化为 `"DEFAULT"`；
  非空值原样保留；
- `format_version`：历史 v1 行由 DDL 默认值补为 1，新写入恒为 2；
  View 中 `max(format_version,1)` 保证读到的代数至少为 1；
- 时间戳统一经 `Clock.now()` 截断到**毫秒**：底层 TIMESTAMP 精度有限，
  若写带纳秒的值，读回精度变化会让"重放响应逐字段一致"不稳定。

**生效时间窗字段的兼容语义（本轮新增，同样确定、稳定）：**

- `effective_at` / `expire_at` / `ttl_seconds`：历史发布单物理上全为 NULL，稳定读作
  "无时间窗"——`actualExpireAt=null`、永不进入到期/生效扫描、永不被自动回滚；
  本次改动不改变任何历史发布单的读取结果；
- `rollback_trigger`：历史回滚行该列为 NULL，稳定归一化为 `MANUAL`
  （旧系统只有人工回滚这一事实）；仍在途的历史行该列为 NULL 就如实读为 null
  （尚未回滚，谈不上来源）。

兼容逻辑集中在 `ConfigService`（`normalizeCategory` 为公开稳定方法），
由 `LegacyCompatibilityTest` 用原生 JDBC 插入"只含 v1 列"的旧行来验证；
时间窗列的历史兼容由 `WindowLegacyAndPaginationTest` 用原生 JDBC 造"窗口列为 NULL 的
在途/回滚发布单"验证。

---

## 7. REST API 摘要

```
POST   /api/entries                       创建条目（body: key,value,description,category）
PUT    /api/entries/{key}                 修改（可带 expectedVersion / expectedValue）
GET    /api/entries/{key}                 查单条
GET    /api/entries?cursor=&limit=        键集分页
GET    /api/entries/{key}/revisions       该条目版本时间线（seq 游标）

POST   /api/releases                      创建发布单（requestId, entryKeys, remark, window?），幂等
POST   /api/releases/{id}/submit          DRAFT -> PENDING
POST   /api/releases/{id}/publish         PENDING -> PUBLISHED（受 effectiveAt 门控）
POST   /api/releases/{id}/rollback        PUBLISHED -> ROLLED_BACK（trigger=MANUAL）
PUT    /api/releases/{id}/window          到点前改期（body: effectiveAt/expireAt/ttlSeconds）
POST   /api/releases/{id}/window/cancel   到点前取消时间窗（此后永不自动回滚）
GET    /api/releases/{id}                 查询
GET    /api/releases?cursor=&limit=       键集分页
GET    /api/releases/windows?bucket=&cursor=&limit=
                                         时间窗维度分页：EXPIRING / OVERDUE / AUTO_ROLLED_BACK
```

`window` 请求体形如 `{"effectiveAt":"...","expireAt":"...","ttlSeconds":600}`，
三字段可省略；`expireAt` 与 `ttlSeconds` 互斥。`ReleaseView` 新增只读字段
`effectiveAt / expireAt / ttlSeconds / actualExpireAt / rollbackTrigger`，
历史发布单对应为 null（`rollbackTrigger` 仅在已回滚时非空，历史回滚为 `MANUAL`）。

错误响应体：`{ "timestamp", "status", "code", "message" }`，`code` 即 §4 的错误码。

---

## 8. 验证方法

### 8.1 全本地、无外部依赖

```bash
mvn -o test                 # 离线跑全部单测/集成测试（H2 内存库，可稳定重复）
```

测试（共 44 个，见 `src/test/.../tests`）：

| 测试类 | 覆盖 |
|---|---|
| `ConfigConcurrencyTest` | 8 线程并发修改仅一个生效，其余 `VERSION_CONFLICT`；`VALUE_MISMATCH` / `ENTRY_KEY_DUPLICATE` / `ENTRY_NOT_FOUND` 可区分；版本记录数与业务版本严格一致（无半更新） |
| `ReleaseLifecycleTest` | 四态全流程；各非法迁移；submit/publish/rollback 重放不重复生效；同 requestId 不同负载被拒；同 requestId 并发提交只落一行且全部返回同一发布单；发布后再改则回滚前置条件失败；空发布单/缺条目 |
| `PaginationTest` | 同毫秒 seq 决胜；满页/末页边界；畸形游标拒绝；版本时间线页数精确；遍历过程中持续写入不重不漏 |
| `LegacyCompatibilityTest` | JDBC 插入 v1 旧行，验证 DDL 默认值 + 归一化的确定语义 |
| `TimeWindowReleaseTest` | 到期自动回滚恢复发布前值并留 AUTO 痕迹；未到期手回后扫描/重复触发收敛同一结论；到期并发触发（手动+自动多线程）只生效一次、全部同状态同来源；到点前改期/取消、到点后改期/取消 `WINDOW_EXPIRED`；生效时刻门控与到点自动发布；TTL 发布时固化到期点；非法窗口可区分；无窗口单永不被扫 |
| `WindowLegacyAndPaginationTest` | JDBC 造窗口列为 NULL 的历史单：稳定读作无窗口、永不被扫；历史 NULL trigger 归一化为 MANUAL；EXPIRING/OVERDUE/AUTO_ROLLED_BACK 三桶分页边界、排序、持续写入不重不漏、畸形游标拒绝 |
| `WindowSchedulerTickTest` | 毫秒级真实定时 tick 到点自动回滚，重复 tick 幂等（固定时钟，离线确定） |
| `WebApiTest` | 真实 HTTP：404/409/400 与错误码、重放响应逐字段一致、状态机、回滚后值恢复、时间窗创建/改期/取消/分桶查询与 INVALID_WINDOW/INVALID_REQUEST |

测试日志用 SLF4J 打印 **输入**（key、版本、期望值、requestId、entryKeys 等）与
**判定依据/结果**（接受或拒绝原因、页码与游标），例如：

```
updateEntry input: key=k, currentVersion=1, expectedVersion=99, expectedValue=<absent>
business rejection: reason=VERSION_CONFLICT, status=409, message=expected version 99 ...
page decided: inputCursor=..., pageSize=5, returned=5, hasNext=true, nextCursor=...
```

每个测试类用 `@DirtiesContext` + 唯一命名的内存库
（`jdbc:h2:mem:testdb-${random.uuid}`）隔离，互不共享数据。

时间窗测试一律注入固定 `Clock`（`Clock.fixedAt`）并在类结束后 `reset`，到期判定不依赖
真实睡眠；测试环境把调度间隔调到极大（`release.window.sweep.fixed-delay-ms`），因此
到期/生效只由测试显式调用 `sweepExpiredReleases / sweepEffectiveReleases` 驱动，
唯一的 `WindowSchedulerTickTest` 用 `@TestPropertySource` 单独把间隔压到毫秒级验证
真实 tick。整套用例可在本地离线、确定性、可重复地运行。

### 8.2 持久化（跨重启）

```bash
mvn -o package -DskipTests
java -jar target/versioned-config-release-store-java-1.0-SNAPSHOT.jar --server.port=8090
# 写入若干条目后 Ctrl+C，再以同样方式重启，GET 数据与版本历史仍在
```

默认文件库位于 `./data/configstore.mv.db`（已在 `.gitignore` 忽略）。
