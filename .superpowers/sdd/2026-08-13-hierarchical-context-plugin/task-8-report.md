# Task 8 实施报告：持久化压缩计划并生成候选节点

## 结果

Task 8 已完成。实现将压缩拆分为 `REQUIRES_NEW` 规划短事务和数据库事务外候选生成，并将 job 从 `PLANNED` 推进到 `GENERATING`，仅在所有 Warm/Hot 节点及所需 Cold 候选均 durable 后进入 `READY_TO_PUBLISH`。本任务未实现 checkpoint 发布、接管恢复协调器或压缩后上下文重建，这些仍属于 Task 9。

## TDD 记录

### RED

先新增：

- `HierarchicalCompactionPlanningTransactionTest`
- `HierarchicalCandidateGenerationServiceTest`
- `HierarchicalRepositoryIntegrationTest` 的 durable Cold candidate 用例
- `HierarchicalSummaryGeneratorTest` 的 retry execution guard 用例

首次按 brief 命令执行时，Maven reactor 在上游模块因找不到指定测试而提前失败。追加 `-Dsurefire.failIfNoSpecifiedTests=false` 后，目标模块按预期因缺少 runtime service、Cold candidate domain/API 而编译失败。随后分别观察到以下行为失败并完成最小修正：

- 取消 guard 未由 mock generator 执行；修正测试使 guard 在模型入口执行，证明模型不会继续调用。
- 已存在父节点仍规划其子孙；调整规划为先复用完整父节点，再按需递归缺失子节点。
- 自然键冲突返回既有 job 时会重复发布 `STARTED`；调整为只有本次生成的 job ID 被持久化时发布一次事件。

### GREEN

最终 Task 8 定向验证覆盖 50 项测试，全部通过；整个 `agent-context-hierarchical` 模块 114 项测试全部通过。

## 主要实现

### 规划短事务

- `HierarchicalCompactionPlanningTransaction.plan` 使用 `@Transactional(propagation = REQUIRES_NEW)`。
- 首先执行 `assertOwnedAndWithinBudget`，再调用 deterministic planner。
- 计划冻结 checkpoint version、strategy generation、execution input boundary、目标 token 预算、Cold/Warm/Hot 范围、候选 node ID、层级与来源类别。
- `plan_document` 不保存消息、旧摘要或候选摘要正文。
- job 首次插入后发布安全 `STARTED` 元数据；自然键冲突返回既有 job 时不重复发布。
- 缺失树节点按 leaf 到 parent 的 bottom-up 顺序规划；完整既有节点直接复用，不生成无用子孙。

### 事务外候选生成

- `HierarchicalCandidateGenerationService` 本身不持有数据库事务。
- 每次摘要 provider 调用前执行 ownership/budget 与 cancellation guard，包括 generator 的第二次纠错重试。
- leaf 从冻结 raw turns 生成；parent 从有序左右直接子节点生成；Cold 从旧 Cold 加按时间排序的 promoted nodes 生成。
- Warm/Hot candidate 通过 `HierarchicalSummaryNodeRepository.insertOrSelect` 幂等持久化。
- 稳定 generator 失败转换为 `HierarchicalCompactionException`，同时将 job 转为 `FAILED` 并通过 execution control 报告安全错误。
- 取消时将 job 转为 `CANCELLED`，不会执行模型调用。

### Durable Cold candidate 架构裁决

现有 immutable summary node 表只允许二进制 Warm/Hot 节点形状，不能安全伪装滚动 Cold。按架构裁决新增插件私有 V2 migration，在原 compaction job 增加 nullable `candidate_cold_document jsonb`，仍保持四张表：

- JSONB envelope 保存 Cold summary body、可信 turn/message coverage、estimated tokens、schema/prompt/provider/model identity 和 candidate ID。
- `plan_document` 仍严格 body-free。
- repository 只允许当前 tenant、conversation、execution、owner、attempt、input boundary 且状态为 `GENERATING` 的 job 写入。
- 首次写入使用 version CAS；重复完全相同候选成功，不同正文或 identity 冲突失败。
- `READY_TO_PUBLISH` 前重新读取并确认 durable Cold candidate 存在且 ID 与计划一致。
- Task 9 可从 READY job 读取 Cold candidate 后执行 checkpoint CAS 发布。

## 验证证据

通过：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCompactionPlanningTransactionTest,HierarchicalCandidateGenerationServiceTest,HierarchicalSummaryGeneratorTest,HierarchicalRepositoryIntegrationTest,HierarchicalFlywayIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`Tests run: 50, Failures: 0, Errors: 0, Skipped: 0`。

通过：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 114, Failures: 0, Errors: 0, Skipped: 0`。

通过：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical spotless:check
git diff --check
```

更广的 `-am` 全测试曾在未受影响的 `agent-persistence` 中遇到既有 `MybatisPlusAuditIntegrationTest` 时间精度 flaky：数据库值比测试 lower bound 少 200ns。Task 8 未修改该模块或测试；随后 Task 8 定向验证和完整 hierarchical 模块验证均通过。

## 边界说明

- 未修改 `HierarchicalConversationContextAssembler`，因此未触碰其 deferred mojibake 注释；按任务说明留待 final wave。
- 未创建 checkpoint publication transaction、recovery service 或 coordinator。
- 未更新根仓库子模块指针。

## Fix round 1/5

### RED

针对首轮审查逐项先补回归测试，并分别观察到预期失败：

- Spring CGLIB 无法代理 `final` planning service，无法证明真实 `REQUIRES_NEW` 语义。
- 唯一边界冲突仍返回本次重算的瞬态 plan，而不是已持久化 job 内的旧 plan。
- 全部节点复用时不会在生成入口执行 ownership/cancellation guard。
- 同 node ID 或 Cold ID 的异常 durable metadata 可以越过仅 ID 校验。
- 生成入口缺少真实 Spring proxy 的 `NOT_SUPPORTED` 事务挂起证明。

### 修复

- 使 planning/generation service 可被 CGLIB 代理，并用真实 Spring transaction proxy 验证：planning 内层事务与外层事务不同，发布失败仅回滚内层，恢复后外层继续提交；generation 会挂起 ambient transaction，方法返回后再恢复。
- `plan_document` 持久化完整 body-free plan、候选树关系和冻结 schema/prompt/provider/model identity；插入冲突后解码并返回持久化 plan，同时拒绝 session、execution、attempt、owner 或 input boundary 不兼容的 job。
- 在生成入口及 `READY_TO_PUBLISH` 前各执行一次 ownership/budget/cancellation guard，包括全部候选复用路径。
- READY 前对每个 durable node 复验 ID、session、level、message range、turn coverage、children、schema/prompt/provider/model、summary body coverage 和 token；对 Cold 复验对应 envelope、正文和 token。
- `CompactionRequest` 与 `PlannedCompaction` 不再携带 raw turns 或 Cold 正文；新增以 execution/checkpoint fence 为 key 的短生命周期 `HierarchicalGenerationSourceProvider`。

### GREEN

定向验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCompactionPlanningTransactionTest,HierarchicalCandidateGenerationServiceTest,HierarchicalSummaryGeneratorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`。

完整模块验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 123, Failures: 0, Errors: 0, Skipped: 0`。
