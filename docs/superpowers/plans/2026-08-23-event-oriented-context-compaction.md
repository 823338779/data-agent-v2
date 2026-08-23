# 事件化上下文压缩与两套机制去 Warm 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将两套上下文压缩机制统一升级为可追溯的 V2 事件摘要；LSM 删除人为 Warm 窗口并只依靠 level/frontier/ROOT 渐进压缩，Hierarchical 重构为“单份滚动 Cold + 最近 10 个完整 Raw 轮次 + 当前完整 ReAct 轮次”。

**Architecture:** 新增 `agent-context-common` 承载 V2 文档、固定提示词、JSON Schema、校验与确定性渲染；LSM 保留 level/fanout/frontier/ROOT 物理算法，删除 Warm cutoff、100～109 轮窗口、`TRIM_WARM` 和 Warm 专用选择器，完整摘要前缀由活动 frontier 按预算确定性渲染；Hierarchical 删除 Warm 二叉树，使用不可变 Cold 版本和活动 checkpoint 原子发布。每次主模型调用仍由现有 `ConversationContextStrategy.prepare(...)` 入口完成水位门禁，动态预算由固定输入、最近 10 轮和当前轮次的实际 token 占用反推。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring AI 2.0、Jackson 3、networknt JSON Schema Validator 3.0.1、MyBatis-Plus 3.5.16、Flyway、PostgreSQL、JUnit 5、AssertJ、Mockito、Testcontainers、Maven。

**Spec:** `docs/superpowers/specs/2026-08-23-event-oriented-compact-context-design.md`

## Global Constraints

- 只支持 `context summary schema v2`；拒绝 V1 和其他版本。
- Hierarchical 不读取、不迁移旧摘要数据；直接以全新的 V1 私有迁移创建滚动 Cold 存储。
- LSM 的 `STANDARD`、`level 0...N`、`ROOT`、Manifest 和 size-tiered compaction 保持不变，但不得再存在逻辑或持久化 Warm 窗口。
- LSM 刚离开最近 10 轮保护区的历史进入 L0，随后只按 fanout、最大层级和 Major Compaction 规则逐层聚合。
- 最近 10 个已完成轮次绝对保持 Raw，不得降为 5 或 0。
- 当前未完成轮次的 user、assistant、thinking、tool call、tool observation 原样保留。
- 每次主模型调用前都要重新组装、计数；超过阈值时先压缩，发布后重新计数，再决定是否提交。
- 完整 V2 JSON 持久化；主模型只接收确定性紧凑渲染。
- `targetRenderedTokens` 是注入文本预算，`maxOutputTokens` 是完整 JSON 输出预算，两者不得混用。
- 压缩失败不得回退为 JSON 注入、Raw 删除、受保护轮次缩减或超限提交。
- 供人工审计的文档使用中文；代码标识符、协议字段、命令和路径保留英文。
- 保持修改范围与本 Spec 一致，不顺手重构无关代码。
- 不创建 commit、branch 或 push；只有用户另行明确授权时才执行 Git 写操作。

---

### Task 1: 建立共享 V2 事件摘要模块

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-context-common/pom.xml`
- Create: `backend/agent-context-common/src/main/resources/schema/context-summary-v2.schema.json`
- Create: `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/summary/EventContextSummaryDocument.java`
- Create: `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/summary/EventContextSummaryPrompt.java`
- Create: `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/summary/EventContextSummaryValidator.java`
- Create: `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/summary/EventContextSummaryRenderer.java`
- Create: `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/summary/EventSummaryCoverage.java`
- Test: `backend/agent-context-common/src/test/java/com/datagent/agentv2/context/common/summary/EventContextSummaryValidatorTest.java`
- Test: `backend/agent-context-common/src/test/java/com/datagent/agentv2/context/common/summary/EventContextSummaryRendererTest.java`
- Test: `backend/agent-context-common/src/test/java/com/datagent/agentv2/context/common/summary/EventContextSummaryPromptTest.java`

**Interfaces:**
- Consumes: 无；这是 LSM 与 Hierarchical 后续任务共同依赖的基础模块。
- Produces:
  - `EventSummaryCoverage(long startTurnSequence, long endTurnSequence, long startMessageSequence, long endMessageSequence)`
  - `EventContextSummaryValidator.schemaDocumentFor(EventSummaryCoverage)`
  - `EventContextSummaryValidator.validate(String, EventSummaryCoverage)`
  - `EventContextSummaryPrompt.systemPrompt(EventSummaryCoverage, int targetRenderedTokens, int maxOutputTokens, boolean correctionAttempt)`
  - `EventContextSummaryRenderer.render(EventContextSummaryDocument)`
  - `EventContextSummaryRenderer.renderCatalog(EventContextSummaryDocument)`

- [ ] **Step 1: 在父 reactor 和新模块 POM 中声明共享模块**

在 `backend/pom.xml` 中把新模块放在 `agent-domain` 之后、两套策略之前：

```xml
<module>agent-domain</module>
<module>agent-context-common</module>
<module>agent-persistence</module>
<module>agent-context-lsm</module>
<module>agent-context-hierarchical</module>
```

新模块依赖 `agent-domain`、Jackson、networknt validator；测试只引入 `spring-boot-starter-test`。

- [ ] **Step 2: 先写 V2 Schema 和校验失败测试**

测试必须构造以下最小合法文档，并分别变异为非法版本、空事件、无来源标题、无来源状态、越界条目来源和错误 coverage：

```java
String valid = """
        {
          "schemaVersion": 2,
          "coverage": {
            "startTurnSequence": 1,
            "endTurnSequence": 2,
            "startMessageSequence": 10,
            "endMessageSequence": 20
          },
          "events": [{
            "title": {"text": "订单统计", "sourceRanges": [{
              "startMessageSequence": 10, "endMessageSequence": 10
            }]},
            "status": {"value": "ACTIVE", "sourceRanges": [{
              "startMessageSequence": 20, "endMessageSequence": 20
            }]},
            "confirmedFacts": [{"statement": "范围已确认", "sourceRanges": [{
              "startMessageSequence": 11, "endMessageSequence": 12
            }] }],
            "userPreferences": [], "workingHypotheses": [], "decisions": [],
            "rejectedApproaches": [], "toolFindings": [], "openQuestions": [],
            "nextSteps": []
          }]
        }
        """;

assertThat(validator.validate(valid, new EventSummaryCoverage(1, 2, 10, 20)))
        .isNotNull();
```

- [ ] **Step 3: 运行共享模块测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-common -am test
```

Expected: FAIL，原因是模块类和 V2 Schema 尚不存在。

- [ ] **Step 4: 实现不可变 V2 文档类型和受 coverage 约束的 Schema**

文档类型使用以下稳定结构；所有 list 在构造器中 `List.copyOf`，所有文本和来源对象执行非空校验：

```java
public record EventContextSummaryDocument(
        int schemaVersion,
        Coverage coverage,
        List<Event> events) {

    public record Coverage(
            long startTurnSequence,
            long endTurnSequence,
            long startMessageSequence,
            long endMessageSequence) {}

    public record Event(
            SourcedText title,
            SourcedStatus status,
            List<Entry> confirmedFacts,
            List<Entry> userPreferences,
            List<Entry> workingHypotheses,
            List<Entry> decisions,
            List<Entry> rejectedApproaches,
            List<Entry> toolFindings,
            List<Entry> openQuestions,
            List<Entry> nextSteps) {}

    public record SourcedText(String text, List<SourceRange> sourceRanges) {}
    public record SourcedStatus(Status value, List<SourceRange> sourceRanges) {}
    public record Entry(String statement, List<SourceRange> sourceRanges) {}
    public record SourceRange(long startMessageSequence, long endMessageSequence) {}
    public enum Status { ACTIVE, COMPLETED, BLOCKED, UNKNOWN }
}
```

JSON Schema 必须固定 `schemaVersion=2`、禁止未知字段、要求 `events` 非空、要求每个事件至少一个语义数组非空，并对 coverage 与所有来源范围注入可信 `const/minimum/maximum`。

- [ ] **Step 5: 实现 validator 的双层校验**

`validate` 先执行 JSON Schema，再转换 record，并通过 Java 代码验证：

```java
public EventContextSummaryDocument validate(
        String document, EventSummaryCoverage expectedCoverage) {
    JsonNode root = json.readTree(document);
    if (!schemaFor(expectedCoverage).validate(root).isEmpty()) {
        throw new IllegalArgumentException(INVALID_DOCUMENT);
    }
    EventContextSummaryDocument parsed =
            json.treeToValue(root, EventContextSummaryDocument.class);
    assertExactCoverage(parsed.coverage(), expectedCoverage);
    assertEveryEventHasSemanticEntry(parsed.events());
    assertSourceRangesWithinCoverage(parsed.events(), expectedCoverage);
    return parsed;
}
```

异常只使用稳定文本 `Invalid event context summary document`，不得拼接正文或 schema 错误详情。

- [ ] **Step 6: 先写渲染器和提示词测试**

断言渲染结果：

```java
assertThat(renderer.render(document))
        .isEqualTo("""
                [事件] 订单统计｜进行中
                事实：范围已确认
                """.stripTrailing());
assertThat(renderer.render(document))
        .doesNotContain("schemaVersion", "coverage", "sourceRanges", "{");
assertThat(renderer.renderCatalog(document))
        .isEqualTo("[事件] 订单统计｜进行中");
```

提示词测试断言包含可信 coverage、`TARGET_RENDERED_TOKENS=800`、`MAX_OUTPUT_TOKENS=2048`、来源不可信、事件独立和仅输出 JSON；同时断言不包含 `SOURCE_MODE=` 或“保留最近 10 轮”。

- [ ] **Step 7: 实现确定性渲染器和固定提示词**

渲染器使用固定标签顺序：事实、偏好、假设、决策、排除、工具、问题、下一步；空分类不输出，标题和 statement 中的 CR/LF 归一为空格，并中和以 `[` 开头的伪分节文本。`renderCatalog` 只输出每个事件的标题和状态，用于判定最小事件目录是否已经超过预算。提示词实现 `correctionAttempt=true` 时只追加固定纠错语句，不包含上一次输出或异常正文。

- [ ] **Step 8: 运行共享模块测试并确认绿灯**

Run:

```bash
cd backend
mvn -pl agent-context-common -am test
```

Expected: PASS；validator、renderer、prompt 三组测试均通过。

---

### Task 2: 扩展摘要模型请求的双预算契约

**Files:**
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextSummaryRequest.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/codex/CodexContextSummaryModelAdapter.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/ollama/OllamaContextSummaryModelAdapter.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model/codex/CodexContextSummaryModelAdapterTest.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model/ollama/OllamaContextSummaryModelAdapterTest.java`
- Create: `backend/agent-domain/src/test/java/com/datagent/agentv2/conversation/context/ContextSummaryRequestTest.java`

**Interfaces:**
- Consumes: Task 1 定义的提示词语义，但不依赖其具体类。
- Produces: `ContextSummaryRequest(..., int targetRenderedTokens, int maxOutputTokens, int inputHardTokenBudget)`。

- [ ] **Step 1: 写双预算参数校验测试**

```java
assertThatThrownBy(
        () -> new ContextSummaryRequest("system", "source", "{}", 0, 1024, 4096))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(
        () -> new ContextSummaryRequest("system", "source", "{}", 256, 0, 4096))
        .isInstanceOf(IllegalArgumentException.class);
```

测试还应证明 `targetRenderedTokens=256`、`maxOutputTokens=1024`、`inputHardTokenBudget=4096` 被原样保存。

- [ ] **Step 2: 运行 domain 聚焦测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-domain -Dtest=ContextSummaryRequestTest test
```

Expected: FAIL，原因是新构造器签名不存在。

- [ ] **Step 3: 修改请求 record，删除旧兼容构造器**

```java
public record ContextSummaryRequest(
        String systemPrompt,
        String sourceDocument,
        String outputSchema,
        int targetRenderedTokens,
        int maxOutputTokens,
        int inputHardTokenBudget) {

    public ContextSummaryRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(sourceDocument, "sourceDocument");
        Objects.requireNonNull(outputSchema, "outputSchema");
        if (targetRenderedTokens <= 0
                || maxOutputTokens <= 0
                || inputHardTokenBudget <= 0) {
            throw new IllegalArgumentException("Summary budgets must be positive");
        }
    }
}
```

不保留四参数或五参数兼容构造器，强制所有调用点显式区分双预算。

- [ ] **Step 4: 更新 provider adapter 测试**

Codex 测试断言 JSON payload 同时包含：

```json
{"TARGET_RENDERED_TOKENS":256,"MAX_OUTPUT_TOKENS":1024}
```

Ollama 测试断言 `numPredict=1024`，并确认 system prompt 中已经包含 `TARGET_RENDERED_TOKENS=256`；adapter 不自行推导或改写目标渲染预算。

- [ ] **Step 5: 更新两个 adapter**

Codex adapter 增加常量并写入 payload：

```java
private static final String KEY_TARGET_RENDERED_TOKENS = "TARGET_RENDERED_TOKENS";

payload.put(KEY_TARGET_RENDERED_TOKENS, request.targetRenderedTokens());
payload.put(KEY_MAX_OUTPUT_TOKENS, request.maxOutputTokens());
```

Ollama 继续用 `maxOutputTokens` 设置 `numPredict`；输入硬预算仍由调用前的 adapter/客户端边界校验执行，不把摘要正文写入异常。

- [ ] **Step 6: 运行 domain 与 provider adapter 测试**

Run:

```bash
cd backend
mvn -pl agent-web -am -Dtest=ContextSummaryRequestTest,CodexContextSummaryModelAdapterTest,OllamaContextSummaryModelAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 3: 将 LSM 摘要生成与注入升级为共享 V2

**Files:**
- Modify: `backend/agent-context-lsm/pom.xml`
- Delete: `backend/agent-context-lsm/src/main/resources/schema/context-summary-v1.schema.json`
- Delete: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryPrompt.java`
- Delete: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryDocumentValidator.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/SummaryGenerationIdentity.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmCompactionPlan.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextAutoConfiguration.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionPlanningTransaction.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionRecoveryService.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssembler.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/ConversationTokenEstimator.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGeneratorTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssemblerTest.java`

**Interfaces:**
- Consumes: Task 1 的 validator/prompt/renderer，Task 2 的六参数 `ContextSummaryRequest`。
- Produces: 所有新 LSM segment 均为 V2；`LsmConversationContextAssembler` 注入渲染文本而非 JSON。

- [ ] **Step 1: 增加共享模块依赖并写 V2 generator 测试**

在 `agent-context-lsm/pom.xml` 增加 `agent-context-common`。测试捕获模型请求并断言：

```java
assertThat(captured.systemPrompt()).contains("context summary schema v2");
assertThat(captured.sourceDocument()).contains("[新增原始对话]");
assertThat(captured.targetRenderedTokens()).isEqualTo(request.targetRenderedTokens());
assertThat(generated.document().schemaVersion()).isEqualTo(2);
```

父级或 ROOT 合并测试断言来源使用多个 `[现有事件摘要]` 区块，不出现 `SOURCE_MODE=`。

- [ ] **Step 2: 运行 LSM generator 测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-lsm -am -Dtest=ContextSummaryGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，当前仍使用 V1 prompt/schema 且请求没有渲染预算。

- [ ] **Step 3: 给 LSM GenerationRequest 增加目标渲染预算**

在 `ContextSummaryGenerator.GenerationRequest` 中加入：

```java
int targetRenderedTokens
```

构造器要求正数。planner 创建每个 operation 时使用该 operation 的预计注入预算；ROOT 的预算不得超过本次 `historyMemoryBudget`，普通 segment 的预算不得超过其计划 token 上限。

同时给 `LsmCompactionPlan.PlannedOperation` 增加同名字段并写入 durable plan JSON，确保 recovery 使用规划时冻结的预算，而不是恢复时重新猜测。

- [ ] **Step 4: 替换 generator 的 V1 依赖**

生成路径统一为：

```java
EventSummaryCoverage coverage = new EventSummaryCoverage(
        request.turnCoverage().startTurnSequence(),
        request.turnCoverage().endTurnSequence(),
        request.messageCoverage().startMessageSequence(),
        request.messageCoverage().endMessageSequence());
ContextSummaryRequest modelRequest = new ContextSummaryRequest(
        EventContextSummaryPrompt.systemPrompt(
                coverage, request.targetRenderedTokens(), maxTokens, correctionAttempt),
        sourceSections(request),
        validator.schemaDocumentFor(coverage),
        request.targetRenderedTokens(),
        maxTokens,
        summaryInputHardTokenBudget);
```

`summaryInputHardTokenBudget` 是 `ContextSummaryGenerator` 构造器注入的正数配置，`LsmContextAutoConfiguration` 使用与 Hierarchical 一致的 `32_768` 默认值。

每次返回后依次执行 V2 校验、完整 JSON token 校验、紧凑渲染、渲染 token 校验。渲染超预算时再估算 `renderCatalog(document)`：目录也超限则抛 `EVENT_CATALOG_OVERFLOW`，否则进入固定纠错重试。第二次请求使用 `correctionAttempt=true`，来源与 coverage 不变。

- [ ] **Step 5: 写 LSM assembler 渲染测试**

构造包含 V2 JSON 的活动 segment，断言最终消息：

```java
assertThat(prepared.messages())
        .extracting(ContextMessage::text)
        .anyMatch(text -> text.contains("[事件] 订单统计｜进行中"))
        .noneMatch(text -> text.contains("\"schemaVersion\":2"));
```

同时断言 memory wrapper 只出现一次，多个 frontier segment 按 coverage 时间顺序渲染。

- [ ] **Step 6: 在 assembler 中注入渲染结果并分离两种估值**

从 repository 取得完整 JSON 后，先使用可信 segment coverage 校验，再渲染：

```java
EventContextSummaryDocument document = validator.validate(
        segment.getSummaryDocument(), expectedCoverage(segment));
dynamic.add(ContextMessage.user(renderer.render(document)));
```

持久化 `estimatedTokenCount` 继续描述完整 JSON；frontier 选择和最终 `PreparedConversationContext.estimatedInputTokens` 改用渲染消息实际估值。

- [ ] **Step 7: 删除 LSM 私有 V1 prompt/schema/validator 并更新装配 Bean**

`LsmContextAutoConfiguration` 注入共享 validator 和 renderer；摘要 Schema 版本升级为 2，提示词版本升级为 5。删除所有 V1 资源引用和 V1 record import。

- [ ] **Step 8: 运行 LSM 摘要与装配测试**

Run:

```bash
cd backend
mvn -pl agent-context-lsm -am -Dtest=ContextSummaryGeneratorTest,LsmConversationContextAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 4: 删除 LSM Warm 窗口并强化每次调用的保护区门禁

**Files:**
- Create: `backend/agent-context-lsm/src/main/resources/db/agent-context-lsm/migration/V2__remove_lsm_warm_window.sql`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmContextManifest.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmContextPolicySnapshot.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmCompactionPlan.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmContextPolicy.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmCompactionPlanner.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmInvariantValidator.java`
- Delete: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/WarmSegmentFrontierSelector.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmInputFrontierSelector.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/LsmSegmentKeyFactory.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/entity/LsmContextManifestEntity.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmContextManifestMapper.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapping/LsmPersistenceStructMapper.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmManifestPublicationTransaction.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionRecoveryService.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssembler.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/strategy/LsmConversationContextStrategy.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmProtectedContextOverflowException.java`
- Delete: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/WarmSegmentFrontierSelectorTest.java`
- Create: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmInputFrontierSelectorTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmCompactionPlannerTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmPlannerStateMachineTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/persistence/mapping/LsmPersistenceStructMapperTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssemblerTest.java`
- Modify: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/strategy/LsmConversationContextStrategyTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionRecoveryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 的 V2 segment 和渲染后实际 token 估值。
- Produces: 不含 Warm 概念的 LSM manifest/planner/frontier；`prepare` 每次调用时要么返回低于阈值的“活动摘要 frontier + 10 个完整 Raw + 当前完整轮次”，要么稳定失败。

- [ ] **Step 1: 先写无 Warm 的 planner、manifest 和 frontier 失败测试**

删除旧的“Warm 保持 100～109 轮”和 `TRIM_WARM` 期望，改写测试证明：

```java
assertThat(plan.operations())
        .extracting(LsmCompactionPlan.PlannedOperation::type)
        .doesNotContain(LsmCompactionPlan.OperationType.TRIM_WARM);
assertThat(plan.proposedManifest().activeSegmentIds()).isNotEmpty();
assertContinuousCoverage(plan.proposedSegments(), summarizedStart, protectedRawStart - 1);
```

新增 `LsmInputFrontierSelectorTest`：选择器面向 Protected Raw 之前的完整摘要 coverage；预算充足时可以展开父段，预算紧张时选择父段；结果必须连续、无重叠、按时间有序，不接收 Warm 起止边界。

- [ ] **Step 2: 写持久化和保护区失败测试并确认红灯**

迁移测试断言 `conversation_context_lsm_manifest` 不再包含 `warm_cutoff_turn_sequence`。映射测试构造全新 manifest，不再设置或推导 Warm 边界。Assembler 测试构造“活动摘要 + 最近 10 个完整轮次 + 当前未完成轮次”超过目标预算的状态，断言：

```java
assertThatThrownBy(() -> assembler.preparePublishedView(manifest, request, policy))
        .isInstanceOf(LsmProtectedContextOverflowException.class)
        .hasMessage("Protected conversation context exceeds the configured threshold");
```

Run:

```bash
cd backend
mvn -pl agent-context-lsm -am -Dtest=LsmCompactionPlannerTest,LsmInputFrontierSelectorTest,LsmPersistenceStructMapperTest,LsmConversationContextAssemblerTest,LsmConversationContextStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL；旧模型仍要求 Warm 边界、旧 planner 仍产生 `TRIM_WARM`，旧 assembler 仍按 Cold/Warm 分区并可能删除 Raw。

- [ ] **Step 3: 破坏性删除 LSM Warm 持久化和领域字段**

V2 迁移执行：

```sql
alter table conversation_context_lsm_manifest
    drop column warm_cutoff_turn_sequence;
```

不迁移该字段，也不增加兼容视图。同步删除 Entity、Mapper SQL、MapStruct 映射、发布事务、恢复路径和 `LsmContextManifest` 中的 Warm 字段及 `warmTurnCount()`。历史 V1 migration 保持不可变，仅用于先建旧表再由 V2 破坏性收敛。

- [ ] **Step 4: 让 planner 只执行 LSM 原生逐层压缩**

删除 `MIN_WARM_TURNS`、`MAX_WARM_TURNS`、`WarmRange`、`trimWarmToRange(...)`、`TRIM_WARM` 和相应 key 输入。规划顺序固定为：

```text
超过保护区的完整 Raw → 封存为 L0 STANDARD
同 level 段达到 fanout → 合并到下一 level STANDARD
达到 maxLevel 或 Major Compaction 条件 → 将最老连续前缀聚合到 ROOT
```

Major Compaction 的候选边界只由活动段的 level、fanout、maxLevel、ROOT coverage 和连续性决定，不得再通过“Warm 之前即 Cold”推导。`LsmInvariantValidator` 改为验证：所有活动授权段覆盖连续、无重叠；ROOT 至多一个且覆盖最老前缀；所有摘要 coverage 结束于 Protected Raw 之前；不存在固定 100～109 轮要求。

- [ ] **Step 5: 用通用 LSM frontier 替换 Warm 专用 selector**

将可复用的父子展开算法迁入 `LsmInputFrontierSelector`。Assembler 不再组装 ROOT/Cold/Warm 分区，而是：

```text
按 manifest 授权解析完整活动摘要 coverage
→ 在 historyMemoryBudget 内选择连续 input frontier
→ 按 coverage 时间顺序渲染所有选中 V2 segment
→ 追加最近 10 个完整 Raw
→ 追加当前完整 ReAct 轮次
```

不得因预算不足丢弃活动摘要段造成 coverage 缺口；需要收缩时必须先由 compaction 生成更高层摘要并重新发布。

- [ ] **Step 6: 删除 `boundedPrepared` 的 Raw 删除循环并固定保护轮数**

把当前删除最老完整 Raw 的循环替换为不修改消息的强校验。`LsmContextPolicySnapshot` 删除 `warmTargetTurns` 和 `warmMaxTurns`，保留 `protectedHotTurns` 用于审计，但构造器必须要求其值等于 10：

```java
if (protectedHotTurns != 10) {
    throw new IllegalArgumentException("LSM protected raw turns must be 10");
}
```

固定输入加最近 10 轮和当前轮次自身超限时抛 `LsmProtectedContextOverflowException`；历史摘要无法压到动态预算时抛稳定压缩预算错误。不得提供 5 轮、0 轮、截断当前轮次或超限提交路径。

- [ ] **Step 7: 让 compaction 和 input frontier 使用动态 `historyMemoryBudget`**

Planner input 增加：

```java
int historyMemoryBudget
```

该值由 assembler 对固定消息、Protected Raw、当前轮次、wrapper 和安全余量计数后计算。Planner 为每个新 segment 冻结 `targetRenderedTokens`；frontier 只允许返回渲染成本不超过该预算的连续授权视图。发布后必须重新读取 manifest、重新渲染和重新计数。

- [ ] **Step 8: 更新 LSM 集成测试并运行模块验证**

将 `warmPublishesWithinConfiguredRangeAndHotKeepsTenCompleteTurns` 改为验证“L0 逐层晋升且 Hot 保持 10 轮”；删除对 `warm_cutoff_turn_sequence` 的 SQL 断言，改为验证该列不存在、活动段 coverage 连续、level/ROOT 随 fanout 推进。恢复测试继续验证候选复用、fencing 和无 Redis 发布，但不得构造 Warm 字段。

Run:

```bash
cd backend
mvn -pl agent-context-lsm,agent-server -am -Dtest=LsmCompactionPlannerTest,LsmPlannerStateMachineTest,LsmInputFrontierSelectorTest,LsmConversationContextAssemblerTest,LsmConversationContextStrategyTest,LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS；基线中的 `warm range boundaries must both be present or absent` 不再可能出现，因为 Warm 边界已从领域和持久化模型删除。

---

### Task 5: 用单 Cold 持久化模型替换 Hierarchical Warm 存储

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/resources/db/agent-context-hierarchical/migration/V1__create_hierarchical_rolling_cold_store.sql`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCold.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCheckpoint.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCompactionJob.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/SummaryRange.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalCheckpointEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalColdEntity.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalCompactionJobEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalColdMapper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalColdRepository.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalCheckpointMapper.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalCompactionJobMapper.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalCheckpointRepository.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalCompactionJobRepository.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapping/HierarchicalPersistenceStructMapper.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalSummaryNode.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/SummaryNodeIdentity.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/FrontierSelection.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalSummaryNodeEntity.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalSummaryNodeMapper.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalSummaryNodeRepository.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalFlywayIntegrationTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 V2 JSON 语义。
- Produces:
  - `HierarchicalCold` 不可变审计记录；
  - `HierarchicalCheckpoint.activeColdId()` 可空活动引用；
  - job 通过 `candidateColdId` 引用最终候选，不再引用 Warm nodes。

- [ ] **Step 1: 写 V3 Flyway 结构测试**

测试迁移后：

```java
assertThat(tableExists("conversation_context_hierarchical_cold")).isTrue();
assertThat(tableExists("conversation_context_hierarchical_summary_node")).isFalse();
assertThat(columnExists("conversation_context_hierarchical_checkpoint", "active_cold_id"))
        .isTrue();
assertThat(columnExists("conversation_context_hierarchical_checkpoint", "active_frontier_document"))
        .isFalse();
```

- [ ] **Step 2: 运行 persistence 集成测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalFlywayIntegrationTest,HierarchicalRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，V3 和 Cold repository 尚不存在。

- [ ] **Step 3: 编写明确丢弃旧私有数据的 V3 迁移**

V3 在事务中删除旧 Hierarchical 私有四表，再按外键顺序创建：

```sql
drop table if exists conversation_context_hierarchical_summary_node;
drop table if exists conversation_context_hierarchical_compaction_job;
drop table if exists conversation_context_hierarchical_checkpoint;
drop table if exists conversation_context_hierarchical_execution_policy;
```

随后重建 execution policy、不可变 Cold、checkpoint、compaction job。Cold 表至少包含：业务 ID、租户、会话、turn/message coverage、完整 JSON、JSON token、rendered token、target rendered token、Schema/Prompt/模型身份、source SHA-256 和审计字段。Checkpoint 只保存可空 `active_cold_id`、已评估消息边界、generation 和 version。

- [ ] **Step 4: 实现单 Cold 领域类型**

```java
public record HierarchicalCold(
        String coldId,
        String conversationSessionId,
        EventSummaryCoverage coverage,
        String summaryDocument,
        int persistedTokenCount,
        int renderedTokenCount,
        int targetRenderedTokens,
        int summarySchemaVersion,
        int summaryPromptVersion,
        String modelProvider,
        String modelName,
        String sourceContentSha256) {}
```

构造器验证 coverage 正向、预算为正、rendered token 不超过 target、V2/提示词身份为正且字符串非空。删除带二叉 level/alignment 约束的旧 `SummaryRange`，Cold 直接复用共享 `EventSummaryCoverage`。

- [ ] **Step 5: 重写 checkpoint 和 job 映射**

Checkpoint 形状固定为：

```java
public record HierarchicalCheckpoint(
        String checkpointId,
        String conversationSessionId,
        String activeColdId,
        long evaluatedUpToConversationMessageSequence,
        long strategyGeneration,
        long version) {}
```

Job 保留 fencing、source checkpoint version/generation、冻结输入边界、目标预算、plan JSON、candidate Cold ID 和状态。删除所有 node/frontier 字段。

- [ ] **Step 6: 实现 Cold repository 和 checkpoint CAS**

Cold 使用确定性 `coldId` 幂等插入；checkpoint 发布 SQL 必须同时校验 tenant/session、expected version、expected generation 和 READY job owner。repository 测试覆盖重复 Cold 插入、跨租户读取拒绝和并发 CAS 只有一次成功。

- [ ] **Step 7: 运行持久化集成测试**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalFlywayIntegrationTest,HierarchicalRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 6: 用预算驱动的滚动 Cold 规划替换二叉树 planner

**Files:**
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/BinarySummaryTreePlanner.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/WarmFrontierSelector.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlanner.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCompactionPlan.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalContextPolicySnapshot.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextProperties.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalContextPolicy.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalInvariantValidator.java`
- Delete: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/BinarySummaryTreePlannerTest.java`
- Delete: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/WarmFrontierSelectorTest.java`
- Create: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlannerTest.java`

**Interfaces:**
- Consumes: Task 5 的 `HierarchicalCold`/checkpoint；现有 `ConversationTurn` 和 token estimator。
- Produces: `HierarchicalCompactionPlan`，只描述 source Cold、可压缩 Raw、Protected Raw 边界、批次和目标渲染预算。

- [ ] **Step 1: 写三种计划测试**

覆盖以下断言：

```java
// 120 个完整轮次，首次压缩
assertThat(plan.sourceColdId()).isNull();
assertThat(plan.rawSourceRange()).isEqualTo(new TurnRange(1, 110));
assertThat(plan.protectedRawRange()).isEqualTo(new TurnRange(111, 120));

// 旧 Cold 1..110，新增到 145
assertThat(plan.sourceColdId()).isEqualTo("cold-110");
assertThat(plan.rawSourceRange()).isEqualTo(new TurnRange(111, 135));
assertThat(plan.protectedRawRange()).isEqualTo(new TurnRange(136, 145));

// 没有新增 Raw，但预算缩小
assertThat(plan.rawSourceRange()).isEqualTo(TurnRange.EMPTY);
assertThat(plan.targetCoverage()).isEqualTo(new TurnRange(1, 135));
```

另测少于 10 个完整轮次时没有可压缩前缀，以及 Protected Raw 自身超限时返回稳定错误。

- [ ] **Step 2: 运行 planner 测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=RollingColdCompactionPlannerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，新的 planner 尚不存在。

- [ ] **Step 3: 简化策略快照**

删除 `warmWindowTurns`；策略版本升级为 2，Schema 版本为 2，提示词版本为 2。快照保留 `protectedCompletedTurns`，但构造器要求：

```java
if (protectedCompletedTurns != 10) {
    throw new IllegalArgumentException("Protected completed turns must be 10");
}
```

`HierarchicalContextProperties` 不再暴露 Warm 配置，也不允许修改保护轮数。

- [ ] **Step 4: 定义新的 plan record**

```java
public record HierarchicalCompactionPlan(
        long inputUpToMessageSequence,
        String sourceColdId,
        TurnRange targetCoverage,
        TurnRange rawSourceRange,
        TurnRange protectedRawRange,
        Long currentIncompleteTurn,
        int historyMemoryBudget,
        List<MessageBatch> batches) {}
```

`MessageBatch` 保存连续 turn/message coverage，不保存消息正文；`batches` 由摘要输入硬预算确定，必须连续、不重叠并完整覆盖 `rawSourceRange`。

- [ ] **Step 5: 实现动态预算计算和 planner 不变量**

planner 接收 assembler 已计算的固定/保护区 token：

```java
int historyMemoryBudget = targetInputTokens
        - fixedPromptAndToolTokens
        - protectedRawTokens
        - currentTurnTokens
        - wrapperTokens
        - safetyMarginTokens;
```

预算非正时抛出 `PROTECTED_CONTEXT_OVERFLOW`。Invariant validator 校验 Cold 从第 1 轮开始、Raw 紧接旧 Cold、目标 coverage 连续、Protected Raw 恰为最后 10 个完整轮次、当前轮次不在任何 batch 中。

- [ ] **Step 6: 运行 planner 测试**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=RollingColdCompactionPlannerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 7: 实现 V2 Cold 生成和超长来源顺序分批

**Files:**
- Modify: `backend/agent-context-hierarchical/pom.xml`
- Delete: `backend/agent-context-hierarchical/src/main/resources/schema/hierarchical-context-summary-v1.schema.json`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryPrompt.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryDocumentValidator.java`
- Delete: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalNodeKeyFactory.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalColdKeyFactory.java`
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalSummaryGenerator.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalGenerationSourceProvider.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalSummaryGeneratorTest.java`
- Delete: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryDocumentValidatorTest.java`

**Interfaces:**
- Consumes: Task 1 共享 V2 组件、Task 2 双预算请求、Task 6 batches。
- Produces: `HierarchicalSummaryGenerator.generate(GenerationRequest)` 返回最终 `GeneratedCold`；不再提供 leaf/parent/node API。

- [ ] **Step 1: 写首次、增量和纯 Cold 生成测试**

测试捕获来源区块：

```java
assertThat(firstRequest.sourceDocument())
        .contains("[新增原始对话]")
        .doesNotContain("[现有事件摘要]");
assertThat(incrementalRequest.sourceDocument())
        .contains("[现有事件摘要]", "[新增原始对话]");
assertThat(recompactRequest.sourceDocument())
        .contains("[现有事件摘要]")
        .doesNotContain("[新增原始对话]");
```

每种请求都断言 `targetRenderedTokens` 等于 plan 的 `historyMemoryBudget`。

- [ ] **Step 2: 写多批次原子结果测试**

给出三个 batch，mock 模型依次返回 coverage `1..40`、`1..80`、`1..110`。断言 generator 只返回最后一个 `GeneratedCold`，每次请求的现有摘要是前一次已校验结果，任一中间调用失败时没有最终结果。

- [ ] **Step 3: 运行 generator 测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalSummaryGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL；旧 generator 仍暴露 leaf/parent/cold tree API。

- [ ] **Step 4: 定义新的 generation 接口**

```java
public record GenerationRequest(
        String tenantId,
        String conversationSessionId,
        HierarchicalCold sourceCold,
        List<GenerationBatch> batches,
        EventSummaryCoverage targetCoverage,
        int targetRenderedTokens,
        int maxOutputTokens,
        int summaryInputHardTokenBudget,
        SummaryGenerationIdentity identity) {}

public record GenerationBatch(
        EventSummaryCoverage coverage,
        List<ConversationTurn> turns) {}

public record GeneratedCold(
        String coldKey,
        String document,
        String renderedText,
        int persistedTokenCount,
        int renderedTokenCount,
        EventSummaryCoverage coverage,
        SummaryGenerationIdentity identity,
        String sourceContentSha256) {}
```

- [ ] **Step 5: 实现每批最多两次生成与硬校验**

每次模型调用后依次：校验 provider/model 身份、V2 Schema、可信 coverage、完整 JSON 输出 token、确定性渲染 token。第一次失败时只切换 `correctionAttempt=true`；第二次失败抛出稳定 `COMPACTION_OUTPUT_INVALID` 或 `COMPACTION_OUTPUT_OVER_BUDGET`。

- [ ] **Step 6: 实现顺序滚动，不持久化活动中间层**

```java
GeneratedCold current = sourceCold == null ? null : fromPublished(sourceCold);
for (GenerationBatch batch : request.batches()) {
    current = generateOne(current, batch.turns(), request);
}
if (request.batches().isEmpty()) {
    current = generateOne(current, List.of(), request);
}
return requireFinalCoverage(current, request.targetCoverage());
```

`HierarchicalGenerationSourceProvider` 负责把 durable plan 中不含正文的 `MessageBatch` 解析为带完整 `ConversationTurn` 的 `GenerationBatch`。只有调用方取得最终结果后才允许写 Cold repository；中间结果不进入 checkpoint 或主上下文。

- [ ] **Step 7: 运行 generator 测试**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalSummaryGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 8: 简化 Hierarchical durable job、恢复和原子发布

**Files:**
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionPlanningTransaction.java`
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCandidateGenerationService.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionClaimTransaction.java`
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCheckpointPublicationTransaction.java`
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionRecoveryService.java`
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinator.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalDurablePlanValidator.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionException.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionPlanningTransactionTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCandidateGenerationServiceTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCheckpointPublicationIntegrationTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionRecoveryTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinatorTest.java`

**Interfaces:**
- Consumes: Task 5 repositories、Task 6 plan、Task 7 generator。
- Produces: 单候选 Cold 的 `PLANNED → GENERATING → READY_TO_PUBLISH → SUCCEEDED` durable 生命周期。

- [ ] **Step 1: 写 durable plan 无正文测试**

序列化后的 plan JSON 只允许边界、ID、预算、batch coverage 和版本：

```java
assertThat(planDocument)
        .contains("historyMemoryBudget", "protectedRawRange", "batches")
        .doesNotContain("summaryDocument", "messageText", "toolResult", "thinking");
```

- [ ] **Step 2: 写发布原子性与失败保持测试**

测试证明：candidate Cold 插入成功但 checkpoint CAS 失败时，旧 checkpoint 仍指向旧 Cold；generator 任一批失败时 job 进入 FAILED 且 checkpoint 不变；成功时 checkpoint 只切换到最终 Cold。

- [ ] **Step 3: 运行 runtime 聚焦测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalCompactionPlanningTransactionTest,HierarchicalCandidateGenerationServiceTest,HierarchicalCheckpointPublicationIntegrationTest,HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL；现有事务仍依赖 nodes/frontier。

- [ ] **Step 4: 重写 planning 与 durable validator**

planning 冻结 execution fencing、input boundary、base checkpoint version/generation、目标预算和 batch ranges。validator 重算 plan SHA-256，并拒绝 current turn 或 Protected Raw 出现在来源 batch 中。

- [ ] **Step 5: 重写 generation service**

service 领取 job 后从 `HierarchicalGenerationSourceProvider` 按 batch coverage 读取消息，调用 Task 7 generator，插入一条最终 Cold，并把 `candidateColdId` 写入 READY job。任何异常只写稳定 failure code。

- [ ] **Step 6: 重写 publication transaction**

发布顺序固定为：execution owner/fence 校验、READY job 校验、candidate V2/coverage/render budget 复验、checkpoint CAS、job SUCCEEDED。不得查询或发布 summary node。

- [ ] **Step 7: 重写 recovery 与 coordinator**

恢复规则：PLANNED/GENERATING 可由新 owner claim 后重新生成；READY_TO_PUBLISH 复验现有 candidate 后发布；SUCCEEDED 直接读取活动 checkpoint；FAILED/CANCELLED 建立新 attempt。coordinator 最多处理 3 次发布竞争，每次竞争后重新加载 checkpoint 和重新执行水位门禁。

- [ ] **Step 8: 运行 durable runtime 测试**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalCompactionPlanningTransactionTest,HierarchicalCandidateGenerationServiceTest,HierarchicalCheckpointPublicationIntegrationTest,HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

---

### Task 9: 重写 Hierarchical 装配器、策略和自动配置

**Files:**
- Rewrite: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/strategy/HierarchicalConversationContextStrategy.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfiguration.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalMapperConfiguration.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/observability/HierarchicalContextMetrics.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalProtectedContextOverflowException.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssemblerTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/strategy/HierarchicalConversationContextStrategyTest.java`

**Interfaces:**
- Consumes: Tasks 5–8 的单 Cold runtime。
- Produces: 每次 `prepare(request)` 返回低于阈值的“一个 Cold 渲染 + 10 个完整 Raw + 当前完整轮次”，或稳定失败。

- [ ] **Step 1: 写固定消息顺序和硬保护测试**

断言顺序：

```java
assertThat(prepared.messages()).containsExactlyElementsOf(
        concat(
                request.fixedSystemMessages(),
                List.of(ContextMessage.system(MEMORY_WRAPPER)),
                List.of(ContextMessage.user(renderedCold)),
                lastTenCompletedRawMessages,
                currentIncompleteMessages));
```

断言没有活动 Cold 时不添加空 JSON；任何状态下最多一条 Cold 记忆消息；Protected Raw 超限时抛 `HierarchicalProtectedContextOverflowException`。

- [ ] **Step 2: 写策略的调用前门禁测试**

三条路径：未超 trigger 不调用 coordinator；首次 Raw 超 trigger 调用并发布 Cold；当前 ReAct 增长导致已发布 Cold 超动态预算时，调用 coordinator 做同 coverage 再压缩。所有成功路径最终 `estimatedInputTokens <= targetInputTokens`。

- [ ] **Step 3: 运行 assembler/strategy 测试并确认红灯**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am -Dtest=HierarchicalConversationContextAssemblerTest,HierarchicalConversationContextStrategyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL；旧装配器仍加载 frontier/nodes 并存在 Hot 降级。

- [ ] **Step 4: 实现单 Cold 装配和预算快照**

Assembler 输出内部 `ContextState`，至少包含：全部 turn、active Cold、Protected Raw、current turn、fixed token、protected token、current token、Cold rendered token、完整候选输入 token 和 history memory budget。它不再加载 node lineage。

- [ ] **Step 5: 重写策略 prepare 流程**

```java
ContextState state = assembler.loadAndEstimate(request, frozenPolicy);
if (!policy.requiresCompaction(state.estimatedTokens(), request.budget())) {
    return assembler.prepare(state);
}
if (state.protectedContextTokens() > frozenPolicy.targetInputTokens(request.budget())) {
    throw new HierarchicalProtectedContextOverflowException();
}
HierarchicalCheckpoint published = coordinator.compactIfRequired(state).checkpoint();
PreparedConversationContext prepared = assembler.preparePublishedView(published, request, frozenPolicy);
policy.assertWithinTarget(prepared.estimatedInputTokens(), request.budget());
return prepared;
```

删除 identity migration、空 V1 JSON、空 frontier 和 active frontier metrics。

- [ ] **Step 6: 清理自动配置中的 Warm Bean**

删除 summary node repository、binary planner、Warm selector 和 node key factory Bean；增加共享 validator/renderer、Cold repository、rolling planner 和 Cold key factory Bean。Mapper configuration 只扫描 execution policy、Cold、checkpoint、job 四类 mapper。

- [ ] **Step 7: 更新指标**

保留输入水位、压缩耗时、checkpoint coverage/generation、压缩结果；删除按 Warm level/frontier 计数的指标。新增 `rendered_cold_tokens`、`target_cold_tokens` 和稳定 failure code 计数，不使用 tenant/session 作为 tag。

- [ ] **Step 8: 运行 Hierarchical 全模块测试**

Run:

```bash
cd backend
mvn -pl agent-context-hierarchical -am test
```

Expected: PASS。

---

### Task 10: 验证每次 ReAct 模型调用都重新经过上下文门禁

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/RawConversationContextStrategy.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/context/ConversationContextStrategyResolverTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactExecutionEngineIntegrationTest.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/RawContextWindowExceededException.java`

**Interfaces:**
- Consumes: 现有 `ReactExecutionEngine` 每次循环调用 `contexts.prepare(contextRequest)` 的行为，以及 Tasks 4/9 的策略门禁。
- Produces: 首次模型调用和每次工具结果后的后续调用都重新准备上下文；raw 策略超限时也不得把非法请求交给 provider。

- [ ] **Step 1: 扩展 ReAct 集成测试**

让模型第一次返回 tool call、工具归档 observation、第二次返回 final answer；对 resolver spy 断言：

```java
verify(contexts, times(2)).prepare(any(ConversationContextRequest.class));
verify(model, times(2)).complete(anyList(), anyList(), any());
```

第二次 `ConversationContextRequest.execution().inputUpToConversationMessageSequence()` 必须覆盖已归档工具结果，证明 ReAct 中间环节不是复用第一次 PreparedContext。

- [ ] **Step 2: 运行 ReAct 集成测试并确认当前行为证据**

Run:

```bash
cd backend
mvn -pl agent-server -am -Dtest=ReactExecutionEngineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS；现有 `while` 循环的每次 invocation 都重新调用 `contexts.prepare`。若失败，先修复恢复分支绕过 prepare 的问题，再继续后续步骤。

- [ ] **Step 3: 给 raw 策略增加硬窗口失败测试与实现**

在 resolver 测试中选择 raw strategy，构造估值超过 `hardInputLimitTokens` 的请求，断言抛出 `RawContextWindowExceededException`，且不会返回超限 `PreparedConversationContext`。Raw 不执行压缩，但必须遵守 provider 硬窗口。

- [ ] **Step 4: 保持 ReactExecutionEngine 入口单一**

如果 Step 2 已证明每次循环调用 `contexts.prepare`，不要在 tool coordinator 或 model gateway 新增重复门禁；只补充注释说明每次 invocation boundary 都重新准备上下文。若测试发现恢复路径跳过 prepare，只修复该恢复分支，使其回到现有：

```java
ConversationContextRequest contextRequest = contextRequests.create(run, boundary, modelTools);
PreparedConversationContext prepared = contexts.prepare(contextRequest);
```

- [ ] **Step 5: 运行 web/server 聚焦测试**

Run:

```bash
cd backend
mvn -pl agent-server -am -Dtest=ConversationContextStrategyResolverTest,ReactExecutionEngineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS；两次模型调用对应两次上下文准备，raw 超限明确失败。

---

### Task 11: 删除两套机制的 Warm 残留并完成全量验证

**Files:**
- Verify: `backend/`

**Interfaces:**
- Consumes: Tasks 1–10 的最终行为。
- Produces: 无 V1、LSM Warm 或 Hierarchical Warm 运行时残留、格式化通过、受影响 Maven reactor 全绿。

- [ ] **Step 1: 扫描废弃符号**

Run:

```bash
rg -n "BinarySummaryTreePlanner|WarmFrontierSelector|HierarchicalSummaryNode|activeFrontierDocument|warmWindowTurns|SOURCE_MODE=" backend/agent-context-hierarchical/src/main/java backend/agent-context-hierarchical/src/test
rg -n "WarmSegmentFrontierSelector|WarmRange|TRIM_WARM|warmStartTurnSequence|warmEndTurnSequence|warmCutoffTurnSequence|warmTargetTurns|warmMaxTurns|MIN_WARM_TURNS|MAX_WARM_TURNS" backend/agent-context-lsm/src/main/java backend/agent-context-lsm/src/test backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionIntegrationTest.java backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionRecoveryIntegrationTest.java
rg -n "hierarchical-context-summary-v1|context-summary-v1|schemaVersion\\\"\\s*:\\s*1" backend/agent-context-common backend/agent-context-lsm/src backend/agent-context-hierarchical/src/main/resources/schema backend/agent-context-hierarchical/src/test
```

Expected: 三条命令均无输出；LSM Flyway V1 中的历史列名不参与运行时代码扫描，V2 负责破坏性删除该列；Hierarchical V3 负责破坏性替换旧树。逐项删除由本变更造成的未使用 import、Bean、mapper 和测试 helper。

- [ ] **Step 2: 运行格式化并检查差异**

Run:

```bash
cd backend
mvn spotless:apply
mvn spotless:check
```

Expected: PASS。随后运行 `git diff --check`，Expected: 无空白错误。

- [ ] **Step 3: 运行共享协议和两套策略测试**

Run:

```bash
cd backend
mvn -pl agent-context-common,agent-context-lsm,agent-context-hierarchical -am test
```

Expected: PASS，0 failures、0 errors。

- [ ] **Step 4: 运行 web/server ReAct 测试**

Run:

```bash
cd backend
mvn -pl agent-web,agent-server -am test
```

Expected: PASS，0 failures、0 errors。

- [ ] **Step 5: 运行完整 backend reactor**

Run:

```bash
cd backend
mvn test
```

Expected: `BUILD SUCCESS`，所有模块 0 failures、0 errors。

- [ ] **Step 6: 对照 Spec 做最终验收**

逐项确认：只支持 V2；完整 JSON 持久化；主模型只见渲染文本；Hierarchical 只有单 Cold；LSM 只通过 level/frontier/ROOT 渐进压缩且没有 Warm 字段或窗口；每次 invocation 前检查；最近 10 轮和当前轮次完整；动态预算传入 prompt 并被代码复验；保护区超限稳定失败；没有 V1 或 Warm 降级路径。

验收报告只列具体命令、退出码、测试数量和仍存在的风险，不声称未运行的检查已经通过。
