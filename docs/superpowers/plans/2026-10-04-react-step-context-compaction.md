# ReAct 步骤级上下文压缩实施计划

> **面向执行 Agent：** REQUIRED SUB-SKILL：使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 逐任务执行本计划；执行方式由用户选择。使用复选框记录验收进度。

**目标：** 同一用户请求在同一 execution 内完成至少 40 个 ReAct 步骤，成功发布并使用至少两次滚动摘要，继续完成原任务。

**架构：** 在既有 Hierarchical 策略中用稳定步骤游标替代整用户轮次压缩边界，保留单份滚动 Cold 和 checkpoint CAS。安全载荷存储支持大工具输入/输出的有界投影与按需读取；每次业务模型调用前统一预算、压缩与组装。产品改动仅归属 `data-agent-v2`。

**技术栈：** 现有 Java、Spring Boot、PostgreSQL/Flyway、MyBatis-Plus、Lombok、MapStruct、Jackson/schema 校验、JUnit/Mockito/Testcontainers 与现有模型调用端口；不引入新依赖。

**规格：** [已确认设计](../specs/2026-10-04-react-step-context-compaction-design.md)。执行前同时读取规格、工作区和本仓库 `AGENTS.md`。

## 全局约束

- 直接使用当前工作区；不创建或切换 worktree，不修改废弃的 `data-agent/`。
- 本计划作为一个功能整体；全部验收与评审完成后创建一个 commit，不逐任务提交，不更新根仓库子模块指针。
- 不修改 Metric Platform、dbt-service、dbt/MetricFlow vendor 的产品代码，不改变业务确认、push、校验和发布授权。
- 保留 LSM/raw 及共享 V2 摘要含义；V3 只用于新的 Hierarchical 策略。禁止删除或弱化已有测试。
- 不新增模块、外部存储服务、tokenizer 或模型供应商依赖；业务模块只调用持久化 Service，不访问 Mapper/MyBatis。
- `H = W - maxOutputTokens - safetyReserveTokens`；默认触发 `0.70H`、目标 `0.55H`，满足 `0 < target < trigger < 1`。
- 最近 2 个完整步骤组只作软保留，可减至 1 或 0；不得固定保护最近 10 个用户轮次。
- 单载荷投影上限 `min(4096, floor(0.10H))` 估算 token；安全原文默认上限 `1048576` 字节；读取默认页上限 `4096` 字节。
- 每次 prepare 只做一次范围规划；每个摘要批次最多 2 次生成尝试，受原 execution deadline 约束；不重置模型/工具累计次数。
- 所有新字段、游标范围、数据库字段及关键逻辑块写中文注释；字符串遵循仓库常量规则。
- 构建命令只在 [README](../../../README.md) 维护。本计划引用该文件的模块测试、Hierarchical/LSM 专项及完整 verify 命令，不另建命令副本。新增验收入口与参数在拥有它的任务中补入 README。
- 确定性模型替身只能验证协议和流程；真实模型的摘要质量单独验证、单独报告。

## 重点审查的五类输入与故障

1. 一个工具的参数或结果突然变得很大：调用/结果仍配对，引用确已保存，超上限不伪装可恢复。任务 3、4、5 覆盖。
2. 同一 assistant 消息追加新 invocation，或一组工具只完成部分：覆盖不能越界，追加不使已冻结前缀失效。任务 1、2、6 覆盖。
3. 外部写操作成功后归档失败，或摘要生成期间取消/CAS 冲突：不重复副作用，不发布失去执行权的候选。任务 3、6 覆盖。
4. 旧 V2 checkpoint 与原始材料不一致，或模型伪造摘要范围：来源不足时明确失败，LSM/raw 仍按原契约运行。任务 2、6、7 覆盖。
5. Skill 激活改变规则/工具预算，同时混有跨分支的相似轮询：必要约束完整保留，估算与发送一致，不错误合并对象。任务 4、5、7 覆盖。

## 文件映射与依赖顺序

以下路径相对于 `data-agent-v2/`。为避免重复长包名，文件清单使用确定性路径别名：

| 别名 | 完整目录 |
|---|---|
| `D` | `backend/agent-domain/src/main/java/com/datagent/agentv2` |
| `H` | `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical` |
| `HT` | `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical` |
| `P` | `backend/agent-persistence/src/main/java/com/datagent/agentv2/context/hierarchical/persistence` |
| `PT` | `backend/agent-persistence/src/test/java/com/datagent/agentv2/context/hierarchical/persistence` |
| `W` | `backend/agent-web/src/main/java/com/datagent/agentv2/conversation` |
| `WT` | `backend/agent-web/src/test/java/com/datagent/agentv2/conversation` |
| `T` | `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool` |
| `S` | `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/runtime` |
| `R` | `backend/agent-server/src/main/java/com/datagent/agentv2/server` |
| `RT` | `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context` |

按任务 1 → 2 → 3 → 4 → 5 → 6 → 7 执行。每个任务有独立测试周期；运行时切换到 V3 在任务 6 完成，避免半成品路径进入默认执行。辅助值对象优先使用所属类型的嵌套 record/enum，不为了计划中的名字拆出大量文件。

## 任务 1：从原始历史形成稳定、完整的步骤组

**文件：** 新增 `D/context/hierarchical/domain/ContextStepCursor.java`、`ContextStepGroup.java`；修改 `H/planning/HierarchicalConversationHistoryLoader.java`；新增 `HT/planning/HierarchicalStepHistoryTest.java`；保留并运行 `HT/planning/HierarchicalConversationHistoryLoaderTest.java`。只有现有用户轮次分组需要传递来源信息时才修改同目录 `ConversationTurnGrouper.java`，不改变 USER 定义轮次的规则。

**接口：**

- `ContextStepCursor(long messageSequence, PositionKind positionKind, long modelInvocationSequence)`，实现 `Comparable<ContextStepCursor>`；嵌套 enum 为 `MESSAGE_START / INVOCATION_END / MESSAGE_END`，非 invocation 位置的 invocation 值为 0。
- `ContextStepGroup(ContextStepCursor fromExclusive, ContextStepCursor throughInclusive, List<ContextMessage> messages, String sourceDigest, boolean complete)`；`sourceDigest` 只覆盖该组冻结的真实来源，不包含随后追加的内容。
- 新增 `HierarchicalConversationHistoryLoader.loadSteps(String conversationSessionId)`，返回 `List<ContextStepGroup>`；保留旧加载接口供兼容测试使用。当前用户锚点由组装层另取，不依赖摘要返回。

- [x] 写 `cursorOrdersWithinOneMessage`、`pendingToolGroupIsNotCompressible`、`appendingInvocationPreservesFrozenPrefix`、`terminalEmptyAssistantDoesNotHideLaterUser`。断言 `start < invocation(1) < invocation(2) < end`；一个 invocation 的两个工具只完成一个时 `complete=false`；后续追加不改变前缀摘要；失败/取消的空消息不遮蔽后续 user。
- [x] 按 README 模块测试入口运行上述测试，确认因缺失步骤能力而失败，排除环境失败。
- [x] 实现游标校验、完整组构建和封口判断。无工具的完成输出也是完整组；PREPARED/RUNNING 组不具备压缩资格；工具失败结果保留为事实。空历史起点使用 `(0, MESSAGE_START, 0)`，真实消息序号为正数。消息封口后才产生 `MESSAGE_END`。
- [x] 验证新测试与原历史加载测试通过；再增加同一 assistant 的三个 invocation 分两次选前缀的断言：既不遗漏后续步骤，也不重复输出已覆盖组。

## 任务 2：独立 V3 摘要范围、增量存储与旧数据转换

**文件：** 新增 `D/context/hierarchical/domain/StepSummaryCoverage.java`、`HierarchicalStepSummaryDocument.java`；新增 `H/summary/HierarchicalStepSummaryProtocol.java`、`H/planning/HierarchicalLegacyCheckpointAdapter.java` 和 `backend/agent-context-hierarchical/src/main/resources/schema/hierarchical-context-summary-v3.schema.json`。修改 `D/context/hierarchical/domain/HierarchicalCold.java`、`HierarchicalCheckpoint.java`，`P/entity/HierarchicalColdEntity.java`、`HierarchicalCheckpointEntity.java`，`P/mapping/HierarchicalPersistenceMapper.java`、`P/json/HierarchicalDocumentCodec.java`、`P/repository/HierarchicalCheckpointRepository.java`。新增插件迁移 `backend/agent-persistence/src/main/resources/db/agent-context-hierarchical/migration/V4__add_step_summary_cursors.sql`。

**测试：** 新增 `HT/summary/HierarchicalStepSummaryProtocolTest.java`、`HT/planning/HierarchicalLegacyCheckpointAdapterTest.java`；扩展 `PT/HierarchicalStoreIntegrationTest.java`、`PT/HierarchicalFlywayIntegrationTest.java`。

**接口：**

- `StepSummaryCoverage(ContextStepCursor fromExclusive, ContextStepCursor throughInclusive, String sourceDigest)`，范围为 `(fromExclusive, throughInclusive]`。
- V3 文档保留目标、约束、事件、已验证事实、待办、错误恢复与证据；所有 evidence 必须落在冻结来源中。模型不决定 checkpoint 游标。
- `HierarchicalStepSummaryProtocol.validate(String json, StepSummaryCoverage expectedCoverage)` 返回 `HierarchicalStepSummaryDocument`；`render(HierarchicalStepSummaryDocument document)` 返回 `String`，用于后续预算与模型输入。
- `HierarchicalLegacyCheckpointAdapter.mapCoveredThrough(HierarchicalCheckpoint legacy, List<ContextStepGroup> source)` 返回 `Optional<ContextStepCursor>`；只有可证明的完整历史范围返回值，空值要求调用方从真实历史重建，不能猜测边界。
- checkpoint 新增 `coveredThrough`、`evaluatedThrough` 和策略/schema 版本，旧字段保留审计含义；既有 CAS 接口扩展其条件，不引入第二套并发控制。

- [x] 写 `rejectsForgedCoverageAndEvidence`、`coveredAndEvaluatedRemainDistinct`、`legacyMappingRequiresCompleteSources`。断言伪造 invocation 或摘要事实引用被拒绝；只观察不覆盖的步骤仍进入后缀；V2 可验证来源映射到消息结束，无法证明时不推进指针。
- [x] 运行新测试确认 RED；迁移测试覆盖已有 V2 数据升级和第二次启动，断言原摘要文档及旧字段未被覆盖。
- [x] 实现独立 V3 schema/校验/渲染与版本化 codec。沿用现有校验库和构造器约定；共享 `EventSummaryCoverage` 和 `context-summary-v2.schema.json` 不变。
- [x] 实现增量迁移、MapStruct 映射和 CAS 条件；保留旧状态直至首次 V3 发布成功。旧来源不存在且无法重建时返回明确恢复失败。不得复制旧迁移中的清空派生数据行为。
- [x] 运行本任务测试及 README 的 Hierarchical、LSM 专项入口，确认 V2/LSM 原有行为通过；插件迁移只随 Hierarchical 加载，raw 无需访问新增表。

## 任务 3：安全载荷归档与按会话读取

**文件：** 新增 `D/context/hierarchical/domain/ContextPayloadReference.java`；在 `P` 新增 `entity/ContextPayloadEntity.java`、`mapper/ContextPayloadMapper.java`、`repository/ContextPayloadRepository.java`；新增插件迁移 `backend/agent-persistence/src/main/resources/db/agent-context-hierarchical/migration/V5__create_context_payload.sql`。修改 `D/conversation/domain/BoundedProcessPayload.java`、`W/process/ConversationProcessCoordinator.java`；新增 `W/process/ContextPayloadArchive.java`、`W/context/ContextResultReaderAdapter.java`。新增 `T/context/ContextResultReader.java`、`T/builtin/ContextResultReadTool.java`；服务装配放在任务 6。

**测试：** 新增 `PT/HierarchicalContextPayloadIntegrationTest.java`、`WT/process/HierarchicalContextPayloadArchiveTest.java`、`WT/context/HierarchicalContextResultReaderTest.java`；保留 `WT/process/ProcessPayloadPolicyTest.java`。

**接口：**

- `ContextPayloadArchive.archive(String sessionId, String executionId, String processItemId, ContextPayloadReference.Direction direction, String safeContent)` 返回 `ContextPayloadReference`；嵌套 `Direction` 为 INPUT/OUTPUT，引用区分完整已保存、超上限、历史缺失，后两者不带可读取正文的假引用。
- `ContextResultReader.read(String executionId, String reference, long offsetBytes, int limitBytes)` 返回该接口的嵌套 `Page`，包含状态、正文、实际起止字节、下一页位置、总字节数和 SHA-256；执行身份由运行时注入，不接受模型提供的 sessionId。
- `ContextResultReader` 仅依赖 `agent-tool` 现有类型和 JDK；适配器在 `agent-web` 查询当前 execution 所属会话及持久化 Service。不为读取工具给 `agent-tool` 添加持久化依赖。

- [x] 写 `archivesSanitizedInputAndOutputBeforeReferencePublication`、`sameSourceAndHashIsIdempotent`、`oversizePayloadHasNoFalseFullReference`。断言只保存 sanitizer 输出、同来源+方向+hash 收敛、`1048577` 字节不被宣称完整存储。
- [x] 写 `readRejectsForeignSessionAndInvisibleSource`、`pageHonorsUtf8Boundaries`、`archiveFailureNeverReexecutesTool`。用中文和 emoji 跨页，断言返回页无破损字符且 nextOffset 前进；默认不超过 `4096` 字节；已成功的模拟写工具执行次数始终为 1。
- [x] 运行 RED 后实现 Entity/BaseMapper/ServiceImpl 和迁移；唯一性由来源+方向+hash 保证。沿用会话可见性及删除/保留路径，避免孤立载荷长期残留；不建立新后台清理系统。
- [x] 接入原工具输入、终态输出归档及事务边界：安全引用落库后才能进入可见过程；外部工具成功而归档失败走现有失败/核实收敛，保留可审计事实，禁止为了补正文自动重跑。
- [x] 实现工具参数校验、页读取和缺失响应。offset 为非负字节位置；不在 UTF-8 边界时前进到下一完整字符并返回实际范围；不足一个完整字符的有效字节预算返回明确错误，不返回无限循环的空页。任意路径、URL、SQL 不是可接受的引用。
- [x] 运行本任务测试 GREEN，并验证无 Hierarchical 归档组件时既有过程持久化仍可工作；不读取日志恢复老预览。

## 任务 4：有界历史投影、可信约束与业务事实

**文件：** 新增 `H/planning/HierarchicalStepProjection.java`、`D/context/hierarchical/domain/ContextExecutionFacts.java`；修改 `H/runtime/HierarchicalConversationContextAssembler.java`、`D/conversation/context/ConversationContextRequest.java`、`W/context/ConversationContextRequestFactory.java`、`S/ExecutionSkillView.java`；新增 `W/context/ContextExecutionFactsFactory.java`。新增 `HT/planning/HierarchicalStepProjectionTest.java`、`WT/context/HierarchicalExecutionFactsTest.java` 和 `backend/agent-skill/src/test/java/com/datagent/agentv2/skill/runtime/HierarchicalSkillConstraintTest.java`。

**接口：**

- `HierarchicalStepProjection.project(ContextStepGroup source, int payloadTokenLimit)` 返回 `ContextStepGroup`，保留源游标、摘要、完整性和工具协议身份，仅有界替换正文。
- `ContextExecutionFacts` 是纯数据 record，内容包括 execution 固定的项目/分支/工作区、已观察 revision、validation/change/query ID、观察状态与真实确认记录的引用；只记录已有事实，不新建业务状态机。
- `ContextExecutionFactsFactory.create(String executionId)` 返回上述事实；从既有过程/业务 Service 构建，禁止跨仓库导入实现类。
- `ExecutionSkillView.readConstraints()` 返回 `List<ReadResult>`，仅含本 execution 成功读取并冻结的资源内容，按路径和 digest 去重。由请求工厂保持来源与指令层级组装，Hierarchical 不依赖 Skill 实现。
- 在 `ConversationContextRequest` 追加事实及所需约束输入时保留兼容构造入口，LSM/raw 不采用新的历史选择逻辑。

- [x] 写 `largeArgumentsAndResultsPreserveToolPairs`、`projectionKeepsAuthoritativeIds`、`currentUserIsPinnedExactlyOnce`。断言投影受 `min(4096, floor(0.10H))` 限制，工具调用 ID/name 不变，历史参数投影从不进入工具执行入口。
- [x] 写 `pollFoldKeepsTransitionsErrorsAndBranchIdentity`。只读白名单对相同对象和分支上下文折叠摘要源，保留首次/最新/终态/错误/状态转换；不同 branch、workspace、release 或任何写工具不合并；原文后缀没有孤立 observation。
- [x] 写 `skillConstraintsSurviveRepeatedCompaction`、`candidateDoesNotBecomeConfirmedPush`。断言必要 Skill 指令仍在、同 digest 不重复、普通工具正文不成为 system 指令；候选/计划不能变成已确认事实。
- [x] 运行 RED 后实现投影、权威事实组装和 Skill 读取快照。轮询折叠只用于摘要源，每个折叠事实携带全部来源范围；必要规则过大留给预算层明确拒绝，不自由摘要删减。
- [x] 验证上述测试 GREEN，原 Skill 激活、工具可见性及业务确认测试通过；原始过程和模型实际执行参数均未被改写。

## 任务 5：按完整输入预算规划与生成摘要

**文件：** 修改 `D/context/hierarchical/domain/HierarchicalContextPolicySnapshot.java`、`H/configuration/HierarchicalContextProperties.java`、`H/planning/HierarchicalContextPolicy.java`、`H/planning/RollingColdCompactionPlanner.java`、`H/domain/RollingColdCompactionPlan.java`、`H/summary/RollingColdSummaryGenerator.java`、`H/runtime/HierarchicalConversationContextAssembler.java`；仅在必要时调整 `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/runtime/RuntimeContextComposer.java` 的冻结贡献传递，不改变 LSM 选择规则。

**测试：** 扩展 `HT/planning/RollingColdCompactionPlannerTest.java`、`HT/summary/RollingColdSummaryGeneratorTest.java`、`HT/runtime/HierarchicalConversationContextAssemblerTest.java`，新增 `HT/planning/HierarchicalStepBudgetTest.java`。

**接口：**

- `RollingColdCompactionPlanner.planSteps(List<ContextStepGroup> groups, StepSummaryCoverage previousCoverage, int hardInputBudget, int necessaryInputTokens, int selectedRuntimeTokens, int summaryEnvelopeTokens)` 返回 `RollingColdCompactionPlan`；初次 previousCoverage 可空。计划新增步骤覆盖和完整组批次，旧轮次字段只供兼容入口。
- `summaryEnvelopeTokens` 包括摘要提示与 schema；每批还计入旧摘要和本批投影。生成器读取步骤计划并调用任务 2 的 V3 protocol，输出仍通过现有生成结果/Cold 构建路径。
- 增量配置统一在 `data-agent.context.hierarchical.step-compaction` 下：`trigger-ratio=0.70`、`target-ratio=0.55`、`retained-completed-groups=2`、`payload-max-bytes=1048576`、`result-page-max-bytes=4096`。V3 默认采用新配置，旧 `.75/.70` 配置只保留旧版本语义，不暗中更换分母。

- [x] 写 `usesHardInputBudgetAsRatioBase`：夹具 W=10000、output=2000、reserve=1000 时 H=7000，trigger=4900、target=3850；完整输入低于/达到阈值分别不触发/触发，覆盖系统消息、工具定义、运行时贡献和当前请求。
- [x] 写 `threeLargeTurnsCanCompact`、`recentTwoGroupsAreSoftPreference`、`necessaryInputAboveTargetButBelowHardCanContinue`、`necessaryInputAboveHardFailsBeforeSummary`。断言可压缩历史过大不再在摘要入口前失败；P>target 且 P<H 时允许有界结果并记录目标未达到。
- [x] 写 `summaryBatchCountsItsOwnPromptSchemaAndPreviousSummary`、`singleLargeGroupProjectsWithoutSplitting`、`sameCoverageCanBeShortened`、`nonShrinkingSummaryStops`。每次摘要请求也受模型预算约束；不可拆工具组；无净缩减不重复循环。
- [x] 运行 RED；实现一次 prepare 一次规划、连续旧前缀和预算内后缀，保留最近组依次降到 1/0。使用已有保守估算器，明确它不是 provider 精确 token 计数，不引入 tokenizer。
- [x] 实现批次生成和输出校验，最多两次格式尝试；使用同一份已选择的运行时贡献进行估算和发送，压缩后不贪心回填新记忆。摘要冲突于权威事实、覆盖不匹配、预算越界均拒绝。
- [x] 运行 GREEN。旧十轮用例保留其旧版本兼容断言，新 V3 行为独立测试；配置校验覆盖非正预算、比例逆序和非正载荷/页上限。

## 任务 6：原子发布、失败收敛和运行时接入

**文件：** 修改 `H/runtime/HierarchicalCompactionCoordinator.java`、`H/strategy/HierarchicalConversationContextStrategy.java`、`H/observability/HierarchicalContextMetrics.java`、`H/configuration/HierarchicalContextAutoConfiguration.java`、`P/repository/HierarchicalCheckpointRepository.java`；新增 `R/context/ContextResultRuntimeConfiguration.java`，按现有注册惯例装配工具和读取适配器。检查 `W/react/ReactExecutionEngine.java`、`W/context/ConversationContextRequestFactory.java`，只改每次调用前所需衔接。更新 `README.md` 的 Hierarchical 配置、升级/回滚、载荷读取及验证说明。

**测试：** 扩展 `HT/runtime/HierarchicalCompactionCoordinatorTest.java`、`WT/react/ReactExecutionEngineMemoryLifecycleTest.java`；新增 `RT/HierarchicalStepRuntimeIntegrationTest.java`。

**接口：** 沿用 `contexts.prepare` → coordinator → repository CAS 发布入口。每次请求冻结 current tools、必要约束、运行时贡献、来源前缀和 checkpoint 版本；生成前后验证执行权，在短事务中发布完整 Cold/checkpoint 组合。`ContextResultRuntimeConfiguration` 只在新 Hierarchical 策略启用受控读取工具，不绕过现有 maximum/initial 可见性限制。

- [x] 写 `cancelDuringSummaryPreventsPublication`、`expiredExecutionPreventsPublication`、`casLoserUsesWinnerWithoutToolReplay`、`laterAppendDoesNotInvalidateCoveredPrefix`。断言取消/deadline 后 pointer 不前进；CAS loser 重新读取胜出状态；只比较冻结覆盖前缀摘要，后续 append 合法。
- [x] 写 `sameFailedFingerprintIsNotRetried`、`overHardBudgetFailsWithoutPush`、`restartLoadsPublishedSummaryAndSuffix`。同历史/工具/预算失败不无限摘要；低于 H 可继续，超过 H 明确失败；重启仍按旧策略收敛 running execution，后续新 execution 恢复有效 checkpoint，不透明重放中断工具。
- [x] 运行 RED；实现短事务发布与失败指纹，未被 checkpoint 引用的候选不进入模型输入。首次 V3 发布失败保留 V2 active 基础；缺失来源不得猜测迁移成功。
- [x] 完成默认策略、工具和归档组件装配。关闭 execution 时释放 Skill/工具/失败指纹等执行内状态；压缩不修改计数器、deadline、工作区选择或业务确认事实。
- [x] 增加分项预算、前后估算量、触发/失败原因、摘要次数、覆盖推进、CAS 冲突及读取结果诊断。正文/凭据不上日志，session/execution/cursor 不作高基数指标标签。
- [x] 更新 README：新旧参数语义、均匀升级要求和匹配数据快照回滚；移除当前默认策略的固定十轮表述，保留历史版本说明。运行本任务 GREEN 和 README 的相关模块测试，验证 raw/LSM 启动不注册读取工具、不加载载荷表。

## 任务 7：单请求多次压缩与真实指标流程验收

**文件：** 新增 `RT/HierarchicalLongReactIntegrationTest.java`、`RT/HierarchicalStepCompatibilityIntegrationTest.java`；保留 `RT/HierarchicalFullHistoryIntegrationTest.java`；更新 `README.md` 的长期可重复验收入口。一次性真实联调脚本只修改工作区 `tmp/metrics-branches/integration/full_flow.py` 及其实际需要的辅助脚本，不作为产品代码提交。临时证据存放 `tmp/`；最终状态写入本计划复选框。

**接口：** 使用既有 HTTP/SSE/模型端口与真实持久化。确定性替身同时实现业务调用和完整 V3 summary 请求/响应，不以固定“摘要成功”标志替代校验。通过持久化 checkpoint、记录的 provider 输入及实际工具执行次数相互验证。

- [x] 写 `oneRequestCompletesFortyStepsWithTwoPublishedSummaries`：一个 user message、一个 execution，夹具模型/工具上限显式设为至少 64；小窗口和确定性大结果触发。断言完整步骤数 >=40，成功发布摘要数 >=2，覆盖单调前进，后续 provider 输入确含已发布摘要，最终执行成功。

  核心断言使用持久化事实和实际 provider 请求采样计算下列局部变量，不能直接读取替身自行宣称的成功值：

  ```java
  assertThat(userMessageCount).isEqualTo(1);
  assertThat(executionCount).isEqualTo(1);
  assertThat(completedStepCount).isGreaterThanOrEqualTo(40);
  assertThat(publishedSummaryCount).isGreaterThanOrEqualTo(2);
  assertThat(publishedSummaryUsedByLaterModelCall).isTrue();
  assertThat(executionSucceeded).isTrue();
  ```

- [x] 加入 `compactionDoesNotResetReactLimits`、`everyProviderRequestKeepsToolPairs`、`threeTurnsDoNotRequireNewSession`、`v2MigrationAndRawLsmContractsRemainValid`。断言负例仍按累计上限终止，所有业务/摘要调用预算内，多工具配对合法；恢复当前 user、旧错误、待办和确认约束。
- [x] 运行 RED 后只补足实现缺口；运行上述用例及原全文历史测试 GREEN。测试可在没有真实模型的情况下重复稳定通过，不依赖轮询速度或机器性能。
- [ ] 扩展原真实联调替身支持 V3 摘要，删除“另开会话继续验证”的规避路径。在同一会话的一条长开发请求中实际触发至少两次压缩并生成候选；随后通过原显式确认流程推送、发布、查询。确认动作仍由用户/测试授权夹具显式提供，不能由摘要代填，也不要求取消业务确认来凑一个 execution。
- [ ] 真实链路只使用现有 `http://127.0.0.1:3000/agent-admin/metrics` 仓库：隔离测试分支，验证两个分支互不干扰、多个会话可选同分支、单会话可按原规则选择分支；平台查询值匹配预期。记录摘要调用、Cold 发布、checkpoint 前进及后续实际使用证据；共享 main SHA 不变。
- [x] 用脱敏样本经已配置真实摘要模型验证目标、Skill 约束、branch/workspace/revision、validation/change/query、错误和确认状态保留，单独记录结果。真实模型不可用时如实报告该验收缺口，不能用替身结果替代质量结论。
- [x] 按 README 运行 Hierarchical、LSM 和受影响模块验证，再运行完整 verify。若存在失败，区分本次回归、环境问题与可复现既有问题，保留原测试；未达到必要验收前不得标记完成。
- [x] 按执行技能完成代码评审并修复与本次有关的问题，只重跑受修复影响的检查。核对根仓库和各受影响仓库状态，保留 Metric Platform 原有 115 项 `AD` 及其他用户变更；清理本次启动的服务、测试分支和隔离数据，保留必要证据。
- [ ] 全部必要验收通过后，仅在 `data-agent-v2` 暂存本功能文件并审查 staged diff，创建本计划唯一 commit：`feat(context): support intra-turn ReAct compaction`。不更新根仓库 gitlink。报告实际检查、commit 和仍有的外部限制。

## 计划自检与执行交接

当前任务 1～6 已实现并完成针对性验证。任务 7 的确定性服务流程、V2 升级/装配兼容和真实模型脱敏质量探针已通过；独立审查发现的两个 Important 已通过 RED→GREEN 修复。完整 `verify` 已通过（718 项，0 失败、0 错误、5 项按配置跳过）；最终针对性 `verify` 42 项全部通过，并重新生成服务 JAR。真实模型探针已单独启用并通过。

真实三服务流程已扩展 V3 摘要、单请求两次发布证据及原会话 C→D 接续，但当前缺少本机 `FLOW_FORGEJO_PASSWORD`，尚未执行本次真实跨产品验收。该项完成前不创建计划 commit，也不将流程替身结果替代真实链路结论。

| 规格范围 | 实现与验收归属 |
|---|---|
| 用户轮次语义、原子组、可增长消息、稳定游标 | 任务 1、2、6 |
| V3、来源校验、旧数据转换、LSM/raw | 任务 2、6、7 |
| 大参数/大结果、安全存储、读取、可见性与保留周期 | 任务 3、4、6 |
| Skill 约束、业务身份/确认事实、只读轮询折叠 | 任务 4、5、7 |
| 完整输入预算、软后缀、分批与净缩减 | 任务 5、6 |
| CAS、取消、失败指纹、重启与副作用不重放 | 任务 3、6、7 |
| 单请求 40 步/两次发布、真实业务与摘要质量 | 任务 7 |

自检结论：五类重点故障均有归属测试；共享接口按任务顺序定义，V3 独立于 V2；测试与构建入口由 README 维护；一次性联调材料位于 tmp；一个计划完成后只创建一个功能提交。

已按用户确认的本会话顺序执行（Native）完成实现、独立审查和代码验证。待补足真实跨产品验收后，按本计划创建唯一功能提交；目前保留工作区改动和执行台账，以便继续。
