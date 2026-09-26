# 可持久化的配置版本发布存储（Versioned Config Release Store）

为配置条目提供「创建 / 修改并留版本 → 发布草稿流转 → 回滚」的一致能力。目标是在
**并发修改、重复提交、非法状态迁移、进程崩溃**四类情形下，结果都**一致、可解释、可恢复**。

- 语言/框架：Java 21 + Spring Boot 3.4（Web 层可选，核心服务不依赖 Spring）。
- 存储：默认进程内存储（测试零外部依赖）；可切换为**文件型仅追加事件日志**获得跨重启持久化。
- 不依赖任何数据库 / 消息中间件 / 网络服务，单测可在本地稳定重复执行。

---

## 1. 快速开始

```bash
# 编译并运行全部测试（不依赖任何外部服务）
mvn test

# 打包
mvn package

# 以文件日志启动（跨重启持久化），不配置则使用纯内存
java -jar target/versioned-config-release-store-java-1.0-SNAPSHOT.jar \
     --app.journal-file=./data/events.log
```

### 编程式使用

```java
// 内存系统（测试用）
ConfigReleaseSystem system = ConfigReleaseSystem.inMemory();
// 或文件持久化：装配时自动重放日志恢复
ConfigReleaseSystem system = ConfigReleaseSystem.fileBacked(Path.of("data/events.log"));

// 1) 创建（产生 v1）与乐观并发修改（携带所基于的版本号）
system.configs().create("feature.toggle", "off");
system.configs().update("feature.toggle", 1L, "on"); // expectedVersion=1 -> 产生 v2

// 2) 发布流转（每个写请求都带 requestId 作为幂等键）
var draft = system.releases().createDraft("feature.toggle", 2L, "req-1");
system.releases().submit(draft.orderId(), "req-2");    // DRAFT -> PENDING
system.releases().publish(draft.orderId(), "req-3");   // PENDING -> PUBLISHED
system.releases().rollback(draft.orderId(), "req-4");  // PUBLISHED -> ROLLED_BACK

// 3) 键集分页（游标不透明）
PageResult<ConfigVersion> page = system.queries().listVersions(null, 50);
system.queries().listVersions(page.getNextCursor(), 50);
```

### HTTP 接口（可选）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/configs` | 创建配置 `{key,value}` |
| PUT  | `/api/configs/{key}` | 修改，body 含 `expectedVersion`（乐观锁） |
| GET  | `/api/configs/{key}` | 当前态（当前版本 + 生效版本） |
| GET  | `/api/configs/versions?cursor&size` | 全局版本键集分页（按时间 + seq） |
| GET  | `/api/configs/{key}/versions?cursor&size` | 单 key 版本键集分页（按版本号） |
| POST | `/api/releases` | 建发布草稿 `{key,version,requestId}` |
| POST | `/api/releases/{orderId}/submit` | `{requestId}` DRAFT→PENDING |
| POST | `/api/releases/{orderId}/publish` | `{requestId}` PENDING→PUBLISHED |
| POST | `/api/releases/{orderId}/rollback` | `{requestId}` PENDING/PUBLISHED→ROLLED_BACK |
| GET  | `/api/releases?cursor&size` | 工单键集分页 |

错误响应固定为 `{ "errorCode": "...", "message": "...", "at": "..." }`，
调用方应依据 `errorCode` 分支处理。

---

## 2. 数据模型

### 2.1 ConfigEntry（配置条目当前态）
一个 `configKey` 唯一对应一个条目。

| 字段 | 含义 |
| --- | --- |
| `configKey` | 业务键，唯一 |
| `currentVersion` | 最新草稿版本号，key 内从 1 单调递增 |
| `currentValue` | 最新版本内容 |
| `publishedVersion` | **当前实际生效**的已发布版本；`0` = 从未发布（后增字段，老数据默认 0） |
| `createdAt` / `updatedAt` | 创建 / 最近修改时间（UTC） |

### 2.2 ConfigVersion（不可变版本快照）
每次成功的创建/修改都产生一条不可变记录。

| 字段 | 含义 |
| --- | --- |
| `(configKey, version)` | 版本唯一标识，`version` 在 key 内从 1 连续递增 |
| `value` | 该版本内容（不可变） |
| `createdAt` | 版本产生时间 |
| `seq` | **全局单调递增序号**，仅按写入顺序分配；分页的最终决胜键 |

### 2.3 ReleaseOrder（发布工单）
针对 `(configKey, version)` 的一次发布流程。

| 字段 | 含义 |
| --- | --- |
| `orderId` | 工单 id（`D-<uuid>`） |
| `configKey` / `version` | 目标配置版本 |
| `status` | 发布状态机当前状态 |
| `seq` / `createdAt` / `updatedAt` | 排序与时间 |
| `submitRequestId` / `publishRequestId` / `rollbackRequestId` | 各动作首次成功所绑定的幂等键（恢复用） |

### 2.4 幂等登记（IdempotencyRecord）
`requestId -> {action, orderId, resultingStatus, completedAt}`。同一 `requestId`
的重复请求直接重放首次结论；同键复用于不同动作 / 不同工单会被拒绝。

### 2.5 事件日志（EventJournal）
仅追加（append-only），一行一条 JSON，行末 `\n` 即「提交标记」。事件类型：
`CONFIG_CREATED`、`CONFIG_UPDATED`、`RELEASE_CREATED`、`RELEASE_STATUS_CHANGED`。
每个事件携带全局 `seq`，状态迁移事件额外携带迁移后的 `effectivePublishedVersion`。

---

## 3. 发布状态与合法迁移

```
                submit(requestId)        publish(requestId)
   DRAFT  ───────────────────────►  PENDING  ───────────────────►  PUBLISHED
     │                                 │                              │
     │  (DRAFT 直接发布/回滚: 拒绝)     │ rollback                     │ rollback
     │                                 ▼ (撤销未生效发布)             ▼
     │                              ROLLED_BACK  ◄───────────────────┘
     │
     └─ ROLLED_BACK 为终态：再次 publish / rollback 一律拒绝
```

- 合法迁移：`DRAFT→PENDING`、`PENDING→PUBLISHED`、`PUBLISHED→ROLLED_BACK`、
  `PENDING→ROLLED_BACK`（取消尚未生效的发布）。
- 其余组合一律 `ILLEGAL_TRANSITION`。
- 只允许发布**高于当前生效版本**的版本（不能倒退发布），否则 `ILLEGAL_TRANSITION`。
- **回滚语义**：`PUBLISHED→ROLLED_BACK` 后，实际生效版本 = 仍处于 PUBLISHED 的工单中的
  最高版本（允许多个版本先后发布、各自独立回滚）；没有则回退到 `0`（从未发布）。
  例：依次发布 v1、v2 → 生效 v2；回滚 v2 的工单 → 回到 v1；再回滚 v1 → 生效 0。

### 稳定错误码（失败原因可区分）

| errorCode | 触发条件 |
| --- | --- |
| `CONFIG_NOT_FOUND` | 配置键不存在 |
| `VERSION_NOT_FOUND` | 配置存在但指定版本号不存在 |
| `VERSION_CONFLICT` | 创建已存在的键；或修改携带的 `expectedVersion` 与当前版本不一致（乐观锁失败） |
| `RELEASE_NOT_FOUND` | 工单 id 不存在 |
| `ILLEGAL_TRANSITION` | 非法状态迁移；发布非更新版本 |
| `IDEMPOTENCY_KEY_CONFLICT` | 幂等键被复用于不同动作或不同工单（与「成功重放」区分） |
| `VALIDATION_ERROR` | 空 key / 空 requestId / 非法版本号 / 非法分页游标或 pageSize |
| `PERSISTENCE_ERROR` | 事件日志损坏、seq 不连续、IO 失败 |

HTTP 粗分类：不存在类 404；冲突/迁移/幂等复用类 409；参数类 400；持久化 500。
**细粒度原因一律以 `errorCode` 为准**，不要解析 `message`。

---

## 4. 并发约束

1. **单一写临界区**：所有写操作在 `StoreCoordinator` 的一把互斥锁内完成
   「业务校验 → 分配 seq → 事件落盘 → 内存态提交」。要么全部成功，要么在落盘前抛出异常、
   无任何副作用，因此**不会留下部分写入 / 半更新状态**。
2. **乐观并发控制（OCC）**：修改必须携带 `expectedVersion`。同一 key 被并发修改时，
   先提交者把版本 +1，其余请求发现版本号不匹配，一律以 `VERSION_CONFLICT` 被拒绝，
   **只有一个结果生效**。message 中给出「请求基于版本 / 存储当前版本」作为判定依据。
3. **幂等请求**：发布相关写接口必须带非空 `requestId`。重复提交命中登记后重放首次结果
   （返回 `replayed=true`），**不再次触发状态迁移、不重复推进生效版本、不产生新工单**；
   重放结论与首次完全一致。同一 requestId 复用于不同动作/目标 → `IDEMPOTENCY_KEY_CONFLICT`。
4. **无锁一致读**：读路径不加锁。版本对象不可变，条目以对象字段整体推进、引用整体替换，
   `ConcurrentHashMap` / `TreeMap` 快照给出一致视图。

---

## 5. 分页：数据持续写入下不重不漏

采用 **键集分页（keyset pagination），不使用 offset**。

- 全局版本按 `(createdAt, seq)` 排序：同一毫秒由全局唯一 `seq` 决胜。
  游标编码「最后一条的 seq」，下一页只取 seq 严格更大的行。
- 单 key 版本按 `version` 排序，游标编码「最后一个版本号」。
- 工单按 `seq` 排序，规则相同。
- 游标为 Base64URL 文本（`v1:seq:<n>` / `v1:ver:<n>`），不透明；损坏 / 前缀错配 /
  pageSize 非法均返回 `VALIDATION_ERROR`，绝不静默跳回首页。

**为什么持续写入也不重不漏**：已访问行的排序键（seq / 版本号）只增且永不变序，
新写入的行排序键更大、只会出现在当前遍历尾部之后；因此不会把未读行顶掉（不遗漏），
也不会让已读行重新进入后续页面（不重复）。测试 `PaginationTest.paginationStableWhileWritesContinue`
在翻页过程中持续写入并验证了这一点。

---

## 6. 崩溃恢复与原子性（持久化）

- 写操作采用 **write-ahead**：先追加完整事件行（写后 `force` 刷盘），再提交内存态。
- **尾部半行**：崩溃发生在结束换行之前，该行在重放时被丢弃，等价于该次提交从未发生，
  不会重放半个状态。
- **日志已落盘、内存未提交即崩溃**：重启重放会重新应用该事件，得到应有状态（不丢已提交数据）。
- **重放校验**：事件 `seq` 必须从 1 连续无重复；配置版本必须连续；状态迁移必须能找到
  对应工单。任一不满足抛 `PERSISTENCE_ERROR`，拒绝在不确定状态下启动。
- 恢复后 `seq` 游标推进到日志最大值，后续写入继续全局单调。
- 纯内存实现 `InMemoryEventJournal` 用于测试，契约相同。

---

## 7. 兼容性规则（老数据 + 新代码）

1. **只允许末尾追加「可空 / 有默认值」字段**，不得改名、改义、删除或复用既有字段。
2. JSON 编解码关闭 `FAIL_ON_UNKNOWN_PROPERTIES`：
   - 老日志缺少新字段：缺失的数值字段取确定默认值。后增字段
     `effectivePublishedVersion` 缺失即为 `0`，语义是「尚无已发布版本」。
   - 出现代码尚不认识的「未来字段」时忽略（前向宽容）。
3. 枚举（事件类型、发布状态、幂等动作）只允许新增，不得重排或复用既有值语义。
4. 领域对象保留老构造器（如 `ConfigEntry` 不带 `publishedVersion` 的构造器），
   新字段取默认值，保证旧调用点语义稳定。
5. 新增字段的新事件是旧字段集合的超集；对应验证见 `BackwardCompatibilityTest`。

---

## 8. 验证方法

全部为本地 JUnit 5 用例，无外部服务、可稳定重复：

```bash
mvn test                      # 运行全部 25 个用例
mvn test -Dtest=ConfigConcurrencyTest     # 并发冲突
mvn test -Dtest=ReleaseIdempotencyTest    # 重复提交 / 幂等键复用
mvn test -Dtest=StateTransitionTest       # 合法 / 非法状态迁移、回滚版本选择
mvn test -Dtest=PaginationTest            # 分页边界与持续写入
mvn test -Dtest=PersistenceRecoveryTest   # 跨重启恢复 / 半行 / 损坏 / seq 缺口
mvn test -Dtest=BackwardCompatibilityTest # 老日志缺字段 / 未知字段的兼容语义
```

| 测试类 | 覆盖点 |
| --- | --- |
| `ConfigConcurrencyTest` | 16 线程同版本并发修改仅 1 生效、其余 `VERSION_CONFLICT`；并发创建同键；失败不产生多余版本；冲突后重读重试成功 |
| `ReleaseIdempotencyTest` | submit/publish/rollback 重复重放与首次一致、不二次生效；同键跨动作/跨工单冲突；20 线程重复提交只 1 次生效 |
| `StateTransitionTest` | 合法链与全部非法迁移；PENDING 撤销；回滚回到上一发布版本；非前进发布拒绝；三类「不存在」错误码可区分；参数校验 |
| `PaginationTest` | 整除/非整除页边界；翻页中持续写入不重不漏；按 key 隔离；坏游标与坏 pageSize 拒绝；同毫秒由 seq 决胜 |
| `PersistenceRecoveryTest` | 文件日志重启后状态/生效版本/幂等键完全一致；半行丢弃；完整坏行报错；seq 缺口拒绝启动；恢复后 seq 继续单调 |
| `BackwardCompatibilityTest` | 老日志缺后增字段取默认 0；未知字段忽略；兼容恢复后可继续演进写入 |

每个用例都通过 SLF4J 打印**输入**（线程数、版本号、requestId、游标等）与
**判定依据**（成功/冲突计数、最终版本、错误码与 message），便于审阅。

---

## 9. 代码结构

```
domain/        ConfigEntry / ConfigVersion / ReleaseOrder(状态机) / ReleaseStatus / PageResult
error/         ErrorCode（稳定错误码） / BusinessException
store/         StoreCoordinator(锁+seq) / ConfigStore / ReleaseStore(含幂等登记)
persistence/   EventJournal 接口 / InMemoryEventJournal / FileEventJournal / JournalEvent
support/       Clock / SystemClock / Json(兼容编解码)
service/       ConfigService / ReleaseService / QueryService / RecoveryService / ConfigReleaseSystem
web/           ConfigReleaseController / GlobalExceptionHandler / WebConfig
```
