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
