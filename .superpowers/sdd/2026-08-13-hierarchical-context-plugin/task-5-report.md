# Task 5 实施报告：Hot/Warm/Cold 二叉规划与 frontier 选择

## 结果

已完成纯确定性的 hierarchical context 规划能力，未调用摘要模型、未写入持久化、未接入插件运行时：

- 预算策略使用冻结快照计算 75% 软触发、70% 目标水位和物理硬输入上限，并校验硬上限高于软触发。
- 按最近 10 轮 Hot、最近 100 轮 Hot+Warm 划分边界；157 个完整问答得到 Cold `1..57`、Warm `58..147`、Hot `148..157`。
- 任意连续区间拆分为按全局问答序号对齐的 `2^level` 范围；父节点只允许由相邻同层且消息顺序连续的子节点构造。
- Cold 迁移仅覆盖现有 Cold 指针之后、Warm 窗口之前的连续范围。
- Warm selector 先保证完整覆盖，再在预算内从新到旧展开父节点；预算收紧时结果自然保留最老历史的粗粒度表示。
- 现有节点无法覆盖或无法满足预算时返回缺失的对齐范围，不调用模型，也不发布部分 frontier。
- invariant validator 拒绝非二进制覆盖、间隙、重叠、父子同时选择、Cold 指针之前的范围、Warm 外范围以及当前未完成轮。
- Hot 只在严格超过硬输入上限时按最老完整问答优先给出降级候选；当前未完成轮永不进入候选。

## TDD 证据

RED：新增三组测试后，使用目标命令并追加 `-Dsurefire.failIfNoSpecifiedTests=false`，确认因缺少 `SummaryRange`、`HierarchicalCompactionPlan`、`FrontierSelection`、planner/selector/validator 类而 `testCompile` 失败。

GREEN：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalContextPolicyTest,BinarySummaryTreePlannerTest,WarmFrontierSelectorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：13 tests，0 failures，0 errors。

完整模块及依赖验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

结果：`agent-domain` 5 tests、`agent-persistence` 38 tests、`agent-context-hierarchical` 44 tests，全部通过。

格式验证：

```powershell
.\backend\mvnw.cmd -f .\backend\agent-context-hierarchical\pom.xml -B spotless:check
git diff --check
```

结果：全部通过。

## 注意事项

- 原 brief 的 reactor 目标测试命令在 `-am` 下会先进入没有这些测试的依赖模块，并被 Surefire 的 “No tests matching pattern” 阻断，因此实际验证追加了 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 全 reactor `spotless:check` 会被本任务未修改的 `agent-persistence` 中 `Phase1MigrationTest.java` 与 `UuidTypeHandlerTest.java` 既存格式差异阻断；本任务所属 module 的 Spotless 独立通过，未修改这些无关文件。
- 本任务只产生规划数据；摘要生成、持久化 mutation 与运行时插件集成留给后续任务。
