# Task 10 实施报告：注册 hierarchical 策略、自动配置和指标

## 结果

Task 10 已完成。`agent-context-hierarchical` 现在通过共享 `ConversationContextStrategy` 暴露唯一 `strategyId=hierarchical` 的业务入口；execution policy 冻结仍是 `prepare` 的首个副作用，随后才读取冻结历史并执行预算判断。低于 75% 触发线直接返回当前正式视图，超过触发线则交给既有 coordinator 恢复、生成和原子发布，再从发布 checkpoint 重建最终视图。

插件的 repository、planner、summary、runtime、metrics 和 strategy Bean 已全部纳入 `strategy=hierarchical` 条件化自动配置，strategy Bean 显式 `@DependsOn("hierarchicalContextFlyway")`。模拟 server 根包组件扫描的回归证明 `raw` 和 `lsm` 不会绑定 hierarchical properties，也不会创建插件 Flyway、Mapper 或 strategy。

本任务未把插件依赖安装到 `agent-server`；该集成边界仍留给 Task 11。

## TDD 记录

### RED 1：策略、自动配置和指标入口

先新增 `HierarchicalConversationContextStrategyTest`、`HierarchicalContextAutoConfigurationTest` 和 `HierarchicalContextMetricsTest`。brief 原命令会在上游模块先被 Surefire 的“无匹配测试”门禁终止，因此追加：

```powershell
'-Dsurefire.failIfNoSpecifiedTests=false'
```

随后确认 `agent-context-hierarchical` 测试编译按预期失败：缺少 strategy、metrics，且 coordinator 的 `CompactionOutcome` 还不可由策略包访问。

### RED 2：generation source 和节点生成指标

在 assembler 与 candidate generation 测试中先定义以下契约：

- generation source 必须同时匹配 execution 冻结输入边界、base checkpoint version 和 strategy generation；
- active frontier 只向指标层交付有限 `level -> count`，不交付 session 或节点 ID；
- 只有本次实际生成并通过 durable fence 持久化的节点才按 level 计数。

测试先因缺少 assembler API、metrics 注入构造器和运行时记录逻辑而失败，再实施最小生产代码。

### RED 3：server 根包扫描隔离

自动配置测试增加与 server 等价的 hierarchical 包组件扫描后，`raw`/`lsm` 均稳定失败：`HierarchicalMapperConfiguration` 被提前发现并尝试创建 Mapper。修复采用最窄边界：Mapper 配置改为只通过 hierarchical auto-configuration 的 `@Import` 生效，同时移除 repository/codec 的组件扫描 stereotype，所有 Bean 改由条件化 auto-configuration 显式创建。

## 主要实现

### 唯一策略入口

- `HierarchicalConversationContextStrategy` 在构造时保存当前配置快照，在每次 `prepare` 中首先调用 `policies.findOrCreate`，并且只使用返回的 frozen policy。
- 严格超过 frozen 75% trigger 才调用 coordinator；等于触发线不压缩。
- 正常 70% target、受保护最低输入高于 target 但仍在 hard limit 内，以及 hard-limit Hot 降级后的正式视图均由 coordinator/assembler 既有安全边界决定。
- 当前 turn 自身越过 hard limit 时，assembler 的稳定失败直接传播，不启动 compaction。

### 条件化自动装配

- 显式装配四个 repository、history loader、estimator、validator、summary generator、planner、assembler、durable planning/generation/claim/recovery/publication/coordinator、metrics 和 strategy。
- generation source provider 直接复用 assembler 的 checkpoint fence 与 history 冻结读取，不复制正文读取逻辑。
- strategy Bean 依赖 `hierarchicalContextFlyway`，确保插件迁移完成后才对共享策略解析器可见。
- `raw`/`lsm` 下即使宿主执行根包组件扫描，也不存在 hierarchical properties、Flyway、Mapper 或 strategy Bean。

### 低基数指标

新增 Micrometer 指标：

- 输入估算 token 与 context window utilization；
- compaction duration 和有限 outcome；
- 按有限二进制树 level 统计的 generated nodes 与 active frontier；
- Cold pointer 与 strategy generation。

所有 meter 都带固定 `strategy=hierarchical`，动态标签只允许 `outcome` 和 `level`。meter identity 不包含 tenant、session、execution、message、summary、tool 或 content；正文也不会传入 metrics API。

## 顺手清理

- 修复 `HierarchicalConversationContextAssembler` 三处 deferred mojibake 注释，并移除 runtime 中遗留的 Task 号注释。
- 为 Task 1 重命名后的共享 `PreparedConversationContext.strategyGeneration` 补充了中立代次字段注释；仅为文档变更，不改变共享 SPI。

## 验证证据

Task 10 定向验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalConversationContextStrategyTest,HierarchicalContextAutoConfigurationTest,HierarchicalContextMetricsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，reactor `BUILD SUCCESS`。

完整插件模块验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：`Tests run: 179, Failures: 0, Errors: 0, Skipped: 0`，包含真实 PostgreSQL 17 的 Flyway、repository、publication 和 recovery 集成测试。

格式与差异验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain,agent-context-hierarchical spotless:check
git diff --check
```

## 边界说明

- 未修改数据库 schema、Flyway migration 或 OpenAPI。
- 未安装 `agent-server` 依赖、未修改 server 配置，Task 11 仍可独立执行。
- 未更新根工作区子模块指针。
