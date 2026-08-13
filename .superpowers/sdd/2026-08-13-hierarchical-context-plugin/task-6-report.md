# Task 6 实施报告：结构化摘要节点生成与验证

## 结果

已完成 hierarchical context 的纯内存摘要候选生成能力，未接入持久化、事务或运行时编排：

- 新增 `context-summary-v1.schema.json`，固定 `schemaVersion=1`、可信 coverage 和七类数组：`confirmedFacts`、`workingHypotheses`、`decisions`、`rejectedApproaches`、`toolFindings`、`nextSteps`、`openQuestions`。
- 每个结构化条目必须包含非空 `statement` 与消息来源闭区间；单字段最多 128 条、单条陈述最多 2048 字符、单条最多 32 个来源范围。
- validator 同时校验 JSON Schema、未知字段和版本、coverage、来源范围、字段体积与 `ConversationTokenEstimator` 的本地 token 上限。
- generator 仅通过共享 `ContextSummaryModelPort` 调用模型；provider token 计数和本地估算任一超限即失败，候选记录两者较大值。
- 首次无效输出仅允许一次纠错重试；第二次 prompt 只追加稳定纠错说明，不携带上一次正文或错误内容；两次均失败时返回稳定代码 `FAILURE_CONTEXT_COMPACTION`。
- 叶来源由完整 `ConversationTurn` 序列化，保留 message、thinking、tool call 与 observation 协议历史；父来源 JSON 数组只包含左摘要、右摘要；Cold 来源只包含可选旧 Cold 和按消息范围升序排列的迁移节点。
- prompt 明确区分事实、假设、决策和排除方案，要求保留标识符、日期、指标口径、过滤条件、单位与工具失败，删除被推翻的结论，并禁止逐字复制 thinking、分析过程或隐藏推理。
- `SummaryGenerationIdentity` 固定会话、schema、prompt 和 provider/model；`HierarchicalNodeKeyFactory` 使用这些维度及来源种类、层级和完整范围生成稳定 SHA-256 key。

## TDD 证据

### RED

brief 原命令在 `-am` 下先进入没有目标测试的 `agent-domain`，被 Surefire 的 “No tests matching pattern” 阻断。追加 `-Dsurefire.failIfNoSpecifiedTests=false` 后到达目标模块：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=ContextSummaryDocumentValidatorTest,HierarchicalSummaryGeneratorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`testCompile` 因缺少 `ContextSummaryDocumentValidator`、`HierarchicalSummaryGenerator` 及其请求/结果类型失败，符合测试先行预期。

### GREEN

同一有效目标命令结果：13 tests，0 failures，0 errors。覆盖：

- 必填字段、字段类型、未知版本/字段、条目数量、陈述长度、coverage、来源范围和 token 上限；
- 首次无效、第二次有效的纠错重试，以及两次无效后的稳定失败；
- 完整叶历史、父节点左右顺序、Cold 旧摘要与迁移节点顺序；
- provider token/model identity 门禁；
- range、schema、prompt、model identity 的 key 稳定性与隔离。

## 最终验证

目标模块完整测试：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical test
```

结果：62 tests，0 failures，0 errors，包括 Testcontainers PostgreSQL 持久化回归以及全部 planning/summary 测试。

格式验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical spotless:check
git diff --check
```

结果：全部通过。

## 注意事项

- `ContextSummaryDocumentValidator#validate(document, maxSummaryTokens)` 负责 schema 与本地估算上限；generator 另行校验共享端口返回的 provider token 数和 provider/model 身份，没有修改 `ContextSummaryModelPort` 契约。
- `HierarchicalCompactionException` 按总计划属于 Task 8 的 runtime 文件。本任务不提前引入运行时类型，生成器暂以稳定消息 `FAILURE_CONTEXT_COMPACTION` 的 `IllegalStateException` 保持失败边界；Task 8 可在服务层转换为计划中的异常类型。
- 完整 `-am test` 曾在无关的 `agent-persistence` 既有 `MybatisPlusAuditIntegrationTest` 时间精度断言失败：PostgreSQL 微秒值比测试纳秒下界早约 400ns，导致 hierarchical 模块被 reactor 跳过。本任务未修改或弱化该测试；随后安装已编译依赖并独立运行目标模块，62 tests 全部通过。
