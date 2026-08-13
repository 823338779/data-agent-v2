# Task 9 实施报告：原子发布 checkpoint 并支持接管恢复

## 结果

Task 9 已完成。实现现在可以从 durable READY job 原子发布 Cold、Warm/Hot frontier、checkpoint generation 与终态 job；新 owner 可在 `PLANNED`、`GENERATING`、`READY_TO_PUBLISH` 以及 publication 已提交等持久化边界继续执行。stale CAS 不会覆盖新 checkpoint，而是由 coordinator 重新加载最新状态后进入有界重试。

Task 10 的策略装配、自动配置与指标仍未纳入本次变更。

## TDD 记录

### RED

首先新增 publication、recovery 和 coordinator 测试，按 brief 的定向命令确认缺少相关 runtime service 时编译失败。随后补充的关键回归依次观察到预期失败：

- durable plan 尚无 publication 所需的 Warm token 预算，schema v3、repository 写入门禁和旧版本稳定失败均不成立。
- publication 直接采用计划中的局部 frontier 时，可留下 Warm coverage gap，且无法仅凭 durable node/range 重新选择完整 frontier。
- 当前 attempt 已持久化 `CANCELLED` 时，recovery 错误返回 `NONE`，存在重新规划风险。
- publication 后重建上下文若直接抛出 hard-limit 异常，execution control 未收到稳定失败状态。

schema v3 与完整 coverage 的组合 RED 共执行 7 项测试，得到 3 个 assertion failure 和 2 个 error；取消态与 publication 后 hard-limit 两项分别稳定复现后再实施最小修复。

### GREEN

最终 Task 9 核心测试共 21 项：publication 8 项、recovery 8 项、coordinator 5 项，均通过。整个 `agent-context-hierarchical` 模块 158 项测试全部通过。

## 主要实现

### 原子 publication

- 新增 `HierarchicalCheckpointPublicationTransaction`，使用 `REQUIRES_NEW` 事务并以 exact `jobId`、tenant、execution、owner、attempt、input boundary、checkpoint version 与 strategy generation 共同 fencing。
- publication 始终重读数据库中的 job、schema v3 body-free plan、immutable summary nodes 和 durable Cold candidate，不信任内存 carrier 中的阶段状态或正文。
- 在 CAS 前复验 node identity、message/turn range、父子关系、Cold coverage、pointer 单调性以及完整 publishable frontier；缺失节点、gap/overlap、parent+child 和 identity 不匹配均失败且保持 checkpoint 不变。
- checkpoint CAS、job `READY_TO_PUBLISH -> SUCCEEDED` 与安全 `COMPLETED` lifecycle event 位于同一事务。CAS stale 返回 `STALE`，不会写坏较新的 checkpoint。
- repository/mapper 的 checkpoint CAS 显式绑定 READY job ID，避免同一 execution 下其他 READY job 被误授权。

### Durable plan v3 与 frontier 重建

- plan schema 升级到 v3，并持久化 `warmTokenBudget = max(0, targetInputTokens - nonWarmInputTokens)`；repository 仅允许新写 schema v3。
- 已存在 schema v1/v2 job 保持可读，但恢复时稳定返回 `Hierarchical plan version X is unsupported; replan required`；未知未来版本继续明确拒绝。
- `WarmFrontierSelector` 增加只消费 durable node identity、节点集合和 range index 的重建入口。它先构造最粗完整 cover，再按 newest-detail 优先在预算内展开，并拒绝 durable gap、overlap 或不可发布覆盖。
- publication 在 Warm 完整覆盖之后，仅追加经过复验、按 oldest-first 连续覆盖的 Hot prefix；整个过程不读取临时消息正文或 generation source。

### 接管恢复与 coordinator

- 新增独立的 `HierarchicalCompactionClaimTransaction`：在 execution lock 下执行 CAS claim，更新 owner/attempt/version fence；同 owner 的幂等 claim 也会重新读取并验证当前数据库状态。
- `HierarchicalCompactionRecoveryService` 优先识别已发布 checkpoint 和当前 attempt 的 durable cancellation；对旧 attempt 的可恢复 job 执行 claim，恢复冻结 policy、模型 identity、输入边界和计划，再复用已有 immutable nodes 继续生成或直接 publication。
- 旧 owner 的 job 状态写入和 Cold candidate 写入均由真实 PostgreSQL SQL fence 拒绝。
- `HierarchicalCompactionCoordinator` 按 recover/plan、generate、publish、重建并复验预算的顺序运行。stale publication 只从最新 checkpoint 重启，循环有固定上限。
- 固定框架加当前 turn 已越过 hard limit，以及 publication 后重建视图越过 hard limit，都会以 `CONTEXT_WINDOW_EXCEEDED` 同步 execution failure；摘要生成或 publication 不变量失败收敛为 `CONTEXT_COMPACTION_FAILED`。

### Stale READY 收敛

- 同一自然键命中旧 base checkpoint 的 `READY_TO_PUBLISH` job 时，planning 通过 CAS 将其重置为当前 schema v3 `PLANNED`，替换 checkpoint 基线并清除旧 Cold candidate。
- 重置仍受 owner、attempt、input boundary、旧 version 与状态约束，竞争失败不会覆盖其他执行者的 durable 状态。

## 验证证据

Task 9 定向验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCheckpointPublicationIntegrationTest,HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终覆盖：`Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`。

完整模块验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 158, Failures: 0, Errors: 0, Skipped: 0`。其中 repository、publication 与 recovery 集成测试均通过 Testcontainers 在真实 PostgreSQL 17 上执行。

格式与差异检查：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical spotless:check
git diff --check
```

结果：全部通过。

更广的 `-am` 全测试曾在未受影响的 `agent-persistence` 中遇到既有 `MybatisPlusAuditIntegrationTest` 时间精度 flaky：数据库实际时间比测试 lower bound 早 100ns，导致 hierarchical 模块尚未执行。Task 9 未修改该模块；随后目标模块完整 158 项测试均通过。

## 边界说明

- 未实现 Task 10 的 Spring 自动装配、策略切换、调度或指标。
- 未新增跨模块依赖或数据库表；沿用插件现有四表与 Task 8 的 durable Cold 列。
- 未更新根仓库子模块指针。

## Fix round 1/5

### RED

针对首轮审查新增 6 个回归，并先确认测试因缺少以下契约而编译失败：

- 不存在将 immutable node 首次写入与 exact job/execution fence 绑定的 repository/SQL API。
- 生成失败的 `GENERATING -> FAILED` CAS 丢失后仍无 stale-owner abandonment 结果。
- recovery 没有 `FAILED` durable action，coordinator 无法阻止当前 attempt 的终态 job 重新进入 planning。

### 修复

- 新增 fenced node insert：使用单条 PostgreSQL `INSERT ... SELECT ... WHERE EXISTS`，同时绑定 `jobId`、tenant、session、execution、owner、attempt、input boundary 与 `GENERATING` 状态；子查询使用 `FOR UPDATE` 与 takeover claim 串行化，避免 Java 预检查与写入之间的竞态。
- generation 入口已加载并验证的 durable nodes 继续直接复用；只有当时确实缺失、经过模型生成的首次结果才调用 fenced insert。fence 丢失或唯一键竞争均不能通过事后 select 伪装本次旧 owner 写入成功。
- 真实 PostgreSQL 回归按“旧 owner 已得到模型结果、new owner claim、旧 owner insert”的顺序证明：旧 owner 首次写入被拒且表中仍无节点；new owner 可写入；旧 owner 随后也不能借相同 identity 返回成功。
- `failJob` 仅在 `GENERATING -> FAILED` CAS 成功时调用 `executions.fail`。CAS 失败会重读 durable job 并抛出稳定 stale-owner abandonment，绝不修改或上报新 attempt。
- recovery 对当前 attempt 的 `FAILED`、`CANCELLED` 和 `SUCCEEDED` 终态先行收敛：匹配 checkpoint 的 `SUCCEEDED` 仍返回 `COMPLETED`；`FAILED` 返回已上报失败；非匹配 `SUCCEEDED` 作为无效 publication 状态请求 coordinator 安全上报 `CONTEXT_COMPACTION_FAILED`。这些路径均不会返回 `NONE` 或重新 planning。

### GREEN

聚焦 repository、generation、recovery 与 coordinator 的验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalRepositoryIntegrationTest,HierarchicalCandidateGenerationServiceTest,HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0`。

完整模块验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 164, Failures: 0, Errors: 0, Skipped: 0`。真实 PostgreSQL 17 fenced insert、claim、恢复和 publication 集成测试全部通过。

## Fix round 2/5

### RED

新增确定性交错回归，使用顺序 mock 固定以下可见性窗口：

1. recovery 首次读取旧 checkpoint。
2. publication 原子提交新 checkpoint 与 `SUCCEEDED` job。
3. `findBoundary` 已观察到 `SUCCEEDED`。
4. 第二次 checkpoint 读取可观察到新 version/generation。

修复前 recovery 直接把首次 checkpoint 不匹配的 `SUCCEEDED` 分类为无效 publication，测试稳定失败：期望 `COMPLETED`，实际 `FAILED`。

### 修复

- 保留首次 `findCompleted` 快路径。
- 仅当当前 attempt 已为 `SUCCEEDED`、但首次 checkpoint 尚不匹配时，执行一次有界 double-check；不引入循环或无界重试。
- double-check 先重新读取最新 checkpoint，再严格使用刚读取的 version 与 strategy generation 查询 completed job，避免把首次旧 generation 与后续 job 状态混用。
- 二次一致读取若匹配则返回新 checkpoint 的 `COMPLETED`；若 checkpoint 缺失或仍无匹配 completed job，才维持稳定 `FAILED` 分类。

### GREEN

完整 recovery 与 coordinator 验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`。

完整模块验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 165, Failures: 0, Errors: 0, Skipped: 0`。
