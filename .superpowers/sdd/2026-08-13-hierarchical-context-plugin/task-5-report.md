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

## Fix round 1/5

### 修复结果

- parent 和缺失父节点规划均只接受全局问答序号对齐的真正 sibling；Warm `2..3` 与真实 `58..147` 的 leaf-only 紧预算不再构造非法 parent，而是返回合法的 aligned build ranges。
- frontier 从新到旧展开时，一旦较新的可展开节点因预算不足而无法展开，立即停止更老节点的展开，防止出现 old fine/new coarse 的精度倒置。
- 新增 aggregate `PlanningInput` 和 `plan(PlanningInput)`，在一次纯函数调用内组合 checkpoint Cold pointer、冻结策略、70% Warm 目标预算、可用节点 selected/missing frontier 和 hard-limit Hot 降级候选。
- 新增 `SummaryNodeIdentity`，从 checkpoint 的 session/provider/model 与冻结策略的 schema/prompt 构造唯一期望身份；selector 和 validator 均拒绝任一维度不一致的节点。
- parent 消息范围要求严格相邻：`right.startMessageSequence == left.endMessageSequence + 1`。

### 分段 TDD 证据

第一段 RED（alignment、recency、message gap）：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=BinarySummaryTreePlannerTest,WarmFrontierSelectorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

失败结果：14 tests 中 2 failures、2 errors。具体为 message gap 未拒绝、asymmetric cost 得到错误的 `[n-1-2, n-3-4, n-5-8]`，以及 Warm `2..3` 和真实 `58..147` 均因构造非对齐 parent 抛出 `Summary range must be aligned to its binary level`。

第一段 GREEN：同一命令 14 tests，0 failures，0 errors。

第二段 RED（generation identity）：`WarmFrontierSelectorTest` 在 testCompile 阶段因缺少 `SummaryNodeIdentity` 失败。

第二段 GREEN：`WarmFrontierSelectorTest` 9 tests，0 failures，0 errors；覆盖 session、schema、prompt、provider、model 五个身份维度，selector 与 validator 均拒绝混用。

第三段 RED（aggregate planner）：`BinarySummaryTreePlannerTest` 在 testCompile 阶段因缺少 `BinarySummaryTreePlanner.PlanningInput` 失败。

第三段 GREEN：`BinarySummaryTreePlannerTest` 7 tests，0 failures，0 errors；aggregate 用例同时验证 Cold promotion、selected frontier、missing frontier、70% Warm budget 与 hard-limit Hot candidates。

### 最终验证

目标测试：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalContextPolicyTest,BinarySummaryTreePlannerTest,WarmFrontierSelectorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：18 tests，0 failures，0 errors。

完整模块及依赖：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

结果：`agent-domain` 5 tests、`agent-persistence` 38 tests、`agent-context-hierarchical` 49 tests，全部通过。

identity 输入没有未决歧义：checkpoint 提供 session/provider/model，frozen policy 提供 schema/prompt；两者版本不一致时规划明确拒绝，不做静默兼容。
