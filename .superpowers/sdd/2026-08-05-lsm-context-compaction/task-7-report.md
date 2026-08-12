# Task 7 实施报告

## 状态

已完成 context summary schema v1、固定 prompt、结构校验器、摘要生成器、确定性 segment key 与 Ollama 非流式摘要适配器。`agent-context-lsm` 只依赖中立 `ContextSummaryModelPort`，未依赖 `agent-web` 或 provider 实现。

## RED 证据

先新增 `ContextSummaryGeneratorTest` 与 `OllamaContextSummaryModelAdapterTest`，再执行 brief 聚焦命令。首次有效运行在 `agent-context-lsm:testCompile` 失败，缺少 `SegmentKind`、`ContextSummaryGenerator`、`ContextSummaryDocumentValidator` 及其不可变 DTO，符合预期 RED。

实现后的中间测试还捕获到 Spring AI builder 会把未配置工具序列化为 `"tools":[]`，违反请求 JSON 不含 `tools` 的要求。改用 Spring AI 公开 `ChatRequest` record 构造器传递 `null tools` 后转 GREEN。

## GREEN 证据

- brief 聚焦测试：`ContextSummaryGeneratorTest` 10 tests、`OllamaContextSummaryModelAdapterTest` 1 test，0 failures，0 errors，reactor BUILD SUCCESS。
- 最终聚焦回归：`ContextSummaryGeneratorTest`、`OllamaContextSummaryModelAdapterTest`、`OllamaAgentModelGatewayTest` 共 14 tests，0 failures，0 errors，BUILD SUCCESS。
- brief 完整命令 `-pl agent-context-lsm,agent-web -am spotless:apply test`：reactor BUILD SUCCESS；`agent-context-lsm` 50 tests 全通过，`agent-web` 51 tests 全通过（2 skipped）。
- 完整命令首次运行曾被既有 `MybatisPlusAuditIntegrationTest` 的 PostgreSQL 微秒精度断言拦截，实际值仅比下界早 200ns；未修改或弱化该测试，相同命令重跑通过。

## 变更文件

### `agent-context-lsm`

- `src/main/resources/schema/context-summary-v1.schema.json`
- `src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryPrompt.java`
- `src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryDocumentValidator.java`
- `src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java`
- `src/main/java/com/datagent/agentv2/context/lsm/summary/LsmSegmentKeyFactory.java`
- `src/main/java/com/datagent/agentv2/context/lsm/domain/LsmSummarySegment.java`
- `src/main/java/com/datagent/agentv2/context/lsm/domain/LsmCompactionPlan.java`
- `src/main/java/com/datagent/agentv2/context/lsm/planning/LsmCompactionPlanner.java`
- `src/main/java/com/datagent/agentv2/context/lsm/planning/LsmInvariantValidator.java`
- `src/test/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGeneratorTest.java`
- 同步 `SegmentKind` 重命名涉及的三个 planner 测试。

### `agent-web`

- `src/main/java/com/datagent/agentv2/conversation/model/OllamaContextSummaryModelAdapter.java`
- `src/main/java/com/datagent/agentv2/conversation/model/OllamaModelProperties.java`
- `src/main/java/com/datagent/agentv2/conversation/model/OllamaAgentModelGateway.java`
- `src/test/java/com/datagent/agentv2/conversation/model/OllamaContextSummaryModelAdapterTest.java`
- `src/test/java/com/datagent/agentv2/conversation/model/OllamaAgentModelGatewayTest.java`

## 实现摘要

- schema v1 固定要求精确 coverage 与八类结构化数组；每个条目必须包含非空 statement 和至少一个 message source range，额外字段被拒绝。
- validator 除 JSON Schema 外还校验候选 coverage 与计划 turn/message coverage 完全相等，以及所有条目来源范围均位于候选消息区间内。
- prompt 明确来源只是数据、不得覆盖 system/tool 约束；system prompt、工具定义、协议固定内容和凭据不得作为摘要来源；raw thinking 只能转成可复用结构化状态，禁止逐字复制。
- generator 对无效或超限输出只重试一次，不截断 JSON；同时使用 provider token count 和本地保守估算执行严格输出上限。
- STANDARD 上限严格为 `min(1536 + 512 * level, 3584)`，ROOT 固定 4096。
- segment key 对 prompt/schema 版本、kind、level、turn/message coverage、有序来源 ID 和来源正文做 SHA-256，重试与 takeover 可确定性复用。
- Ollama summary 使用 configured model、`stream:false`、schema object format、无 `tools` 字段、`num_ctx:65536` 和请求层级的 `num_predict`，无 delta callback。
- 普通 Ollama streaming gateway 同步发送 `num_ctx`；properties 新增 `contextWindowTokens=65536` 与 `safetyReserveTokens=4096`。

## 自审

- `agent-context-lsm` 没有对 `agent-web`、Spring AI 或 Ollama 的编译依赖；provider 只存在于 web adapter。
- 不记录 prompt、来源、provider 正文或 schema 校验详情；异常只携带稳定无正文消息。
- 新增 record 字段与逻辑块均有中文用途注释，字符串协议与稳定消息均使用静态常量。
- Spotless 对两个 `agent-persistence` 测试和两个无关 `agent-web` 文件产生机械变化，已逐行恢复；最终状态只包含 Task 7 的 LSM 与 Ollama 变更及本报告。
- `git diff --check` 通过；未新增依赖、凭据、生成文件或日志正文。

## Commit

- Message：`feat: generate structured context summaries`
- Commit：本报告所在 Task 7 提交。

## Concerns

- provider `eval_count` 缺失时 adapter 使用正文 code point 数作为保守估算；generator 还会再次执行本地 summary document 估算，因此不会因缺失 usage 放宽上限。
- `safetyReserveTokens` 本任务只完成配置承载；后续上下文预算/组装任务负责把该保留量从可用业务输入中扣除。
- 当前无阻塞。
