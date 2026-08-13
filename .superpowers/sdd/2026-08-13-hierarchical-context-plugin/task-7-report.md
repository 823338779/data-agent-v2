# Task 7 实施报告：组装当前和已发布的模型上下文

## 结果

已在 `agent-context-hierarchical` 内实现当前视图、正式发布视图和压缩规划输入的组装边界，未引入压缩事务、候选模型生成或 checkpoint 发布流程。

新增能力：

- `loadAndEstimate` 在任何压缩判断前读取冻结消息边界、当前 checkpoint 和已发布 frontier，完整估算 fixed input、system、tools、Cold、Warm、Hot、当前未完成轮及消息/tool 协议开销。
- 初始 checkpoint 下的当前视图保留全部 raw 历史；已有 Cold/frontier 时只读取正式发布覆盖尾部之后的 raw turn，绝不把 Cold 指针及已发布节点覆盖范围内的原文再次发送。
- 最终消息顺序固定为 fixed system、Cold 不可信记忆 system、按 checkpoint 文档顺序且同时按时间连续的 Warm/紧急摘要、raw Hot、当前未完成轮。
- frontier 私有文档严格限定为 `{schemaVersion:1,nodes:[nodeId...]}`，拒绝错误版本、未知字段、空 ID 和重复 ID。
- 节点仓储新增 tenant + conversation session + ID 集合读取；assembler 不依赖数据库返回顺序，按 checkpoint ID 顺序重排，并拒绝缺失、额外、重复或 generation identity 不匹配的节点。
- 发布覆盖必须从 Cold 指针后的下一完整问答开始连续前进；gap、overlap、父子/后代同时出现或节点范围不匹配均以稳定安全错误失败。
- 最近 10 个完整 Hot turn 在 70% 目标与 hard limit 之间保持 raw，并通过 runtime 私有 `CompactionInput#targetReached=false` 保留最低水位结果。
- 只有完整估算严格超过 hard limit 时，binary planner 才提供按最老 turn 优先的 Hot 降级范围；正式 checkpoint 可用紧急摘要节点替换对应 raw turn，但不移动 Cold 指针。
- fixed framework、tools 和当前未完成轮自身超过 hard limit，或正式发布视图降级后仍超限时，抛出稳定 `CONTEXT_WINDOW_EXCEEDED`。

## 变更文件

- 新增 `runtime/HierarchicalConversationContextAssembler.java`
- 新增 `runtime/HierarchicalContextWindowExceededException.java`
- 新增 `runtime/HierarchicalConversationContextAssemblerTest.java`
- 扩展 `HierarchicalSummaryNodeMapper` 和 `HierarchicalSummaryNodeRepository` 的安全 ID 读取
- 扩展 `HierarchicalRepositoryIntegrationTest` 的真实 PostgreSQL 租户/会话隔离验证

## TDD 记录

### RED

先创建 assembler 行为测试，再运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalConversationContextAssemblerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果为预期测试编译失败：缺少 `HierarchicalConversationContextAssembler`；上游模块通过，失败原因与目标功能精确对应。

### GREEN

实现最小生产代码后使用相同 focused 命令：

- `Tests run: 7`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

真实 repository 边界验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalRepositoryIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果为 12/12 通过；当前 tenant + conversation session 可以读取指定节点，其他 tenant 无法读取相同会话/节点 ID。

## 最终验证

格式验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical spotless:check
```

结果：`BUILD SUCCESS`，53 个 Java 文件 clean。

完整模块与依赖验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

首次运行在上游既有 `MybatisPlusAuditIntegrationTest` 出现一次 400ns 的 PostgreSQL 微秒精度边界 flake，hierarchical 模块尚未执行；未修改或弱化该测试。原命令重新运行后：

- `agent-domain`：5 个测试，0 failures/errors
- `agent-persistence`：38 个测试，0 failures/errors
- `agent-context-hierarchical`：98 个测试，0 failures/errors
- reactor `BUILD SUCCESS`

附加检查：

- `git diff --check` 通过。
- 新增生产代码不引用 `agent-context-lsm`、`agent-web` 或 `agent-server`。
- 未实现 Task 8/9 的压缩事务、摘要模型调用、候选生成或 checkpoint CAS 发布。
- 根仓库仅显示预期的 `data-agent-v2` 子模块工作区变化。

## 已知提示

- 测试仍显示仓库既有的 Mockito inline mock maker 动态 agent 提示和 javac annotation processor 提示；本任务未新增依赖或修改全局测试配置。

## Fix round 1/5：保护 Hot frontier 与严格 JSON key

本轮仅修正 Task 7 的 read-side 发布校验，未实现 Task 8/9：

- `publishedDynamic` 的所有入口都重新使用当前完整历史和固化 policy 计算 Cold/Warm/Hot 边界，checkpoint 不能把当前受保护 Hot 伪装成 Cold。
- 正常 Warm frontier 仍可在低于 hard limit 时发布；frontier 一旦进入 Hot，assembler 会先撤回全部 Hot 摘要并重建 pre-degradation 完整输入，只有该输入严格超过 hard limit 才允许紧急降级。
- 紧急 Hot 节点必须逐个精确匹配 planner 给出的 level-0 候选，并且只能形成 oldest-first 连续前缀，不能跳过最老候选或任意选择较新 turn。
- frontier JSON 使用 `StreamReadFeature.STRICT_DUPLICATE_DETECTION` 解析，重复 `schemaVersion` 或 `nodes` key 均以稳定 frontier 错误拒绝。
- 回归测试把最近 10 个完整 Hot turn 的 raw 消息和正式发布消息顺序改为完整精确序列断言。

### RED

先添加上述回归测试并运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalConversationContextAssemblerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果为预期 RED：`Tests run: 11, Failures: 4, Errors: 0, Skipped: 0`。四个失败精确对应：低于 hard 的 Hot frontier 被错误接受、Cold pointer 跳过最老 Hot 候选、Cold pointer 选择任意较新 Hot turn、重复 JSON key 被错误接受。把原正式发布夹具扩展为合法的 102 completed turns Cold/Warm/Hot 场景后，同一命令仍保持相同四个目标失败，没有引入额外失败。

### GREEN

完成最小生产修复后，以相同 focused 命令验证：

- `Tests run: 11`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

真实 repository 集成验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalRepositoryIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果为 `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

格式验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am spotless:apply spotless:check
```

结果为 `BUILD SUCCESS`；命令对两个上游既有测试产生的纯格式变化已立即撤回，最终 diff 仅包含 Task 7 assembler、测试和本报告。

全模块及依赖验证：

```powershell
.\backend\mvnw.cmd -q -f .\backend\pom.xml -pl agent-context-hierarchical -am test
```

首次明确退出码运行命中仓库既有 `MybatisPlusAuditIntegrationTest` PostgreSQL 时间精度 flake：实际时间比断言下界低 `100ns`，`agent-persistence` 为 38 tests / 1 failure；未修改或弱化该测试。原命令重跑后 Surefire 报告为：

- `agent-domain`：5 tests，0 failures/errors
- `agent-persistence`：38 tests，0 failures/errors
- `agent-context-hierarchical`：102 tests，0 failures/errors
- 合计 145 tests，0 failures/errors/skipped

附加检查：`git diff --check` 通过；未修改 Task 8/9 相关压缩事务、摘要模型调用或 checkpoint CAS 发布流程。
