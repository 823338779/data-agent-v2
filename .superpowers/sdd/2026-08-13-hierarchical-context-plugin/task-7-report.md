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
