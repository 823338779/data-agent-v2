# Session-Wide Hierarchical Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Hierarchical 基于会话全部消息按 user 边界重建有序轮次，固定原文保护最近 10 轮，并彻底消除历史空 assistant 劫持后续问题的行为。

**Architecture:** 保留共享 `inputUpToMessageSequence` 和现有有界查询，确保 Raw 与 LSM 零行为变化；为 Hierarchical 增加独立的全量会话查询。Hierarchical 内部统一使用不带完成状态的 `List<ConversationTurn>`，Cold 只覆盖最近 10 轮之前的连续前缀，消息序号只作为排序、覆盖和 checkpoint 水位。

**Tech Stack:** Java 21、Spring Boot 4、Maven、多模块、MyBatis-Plus、PostgreSQL 17、Flyway、JUnit 5、AssertJ、Mockito、Testcontainers。

**Spec:** `docs/superpowers/specs/2026-08-25-session-wide-hierarchical-context-design.md`

## Global Constraints

- 只支持单实例后端；同一会话最多一个 `running` execution，活动 execution 期间禁止追加 user 消息。
- 修改范围限于公共消息查询能力、Hierarchical 历史重建/规划/组装/迁移、配置文档和验证。
- `agent-context-lsm` 生产代码、配置、迁移、查询边界、manifest、segment、规划、压缩与组装不得修改。
- Raw 继续调用现有有界历史查询，行为不得修改。
- 共享 `ContextExecutionRef.inputUpToMessageSequence` 与 `LocalAgentExecution.inputUpToMessageSequence` 本期保留。
- Hierarchical 不读取 triggering message ID，也不根据 assistant 正文或 execution 状态区分历史与当前轮次。
- 最近原文轮数固定为 10，只在 Hierarchical 策略领域对象中保留一个事实源，不暴露服务级配置。
- 新增或修改的核心代码使用充分、准确的中文类注释和业务原因注释；禁止机械逐行注释。
- 数据库数据迁移只清理 Hierarchical 派生 checkpoint/Cold，不得修改原始 session/message/execution/thinking 或任何 LSM 表。
- 每个任务先写失败测试、确认失败原因，再实现最小代码并运行范围匹配的 Maven 验证。
- 不修改无关代码，不格式化无关文件，不提交凭据或本地生成文件。

---

## File Structure

### 公共持久化

- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
  - 新增按 session 读取全部真实 user/assistant 中心消息的方法；保留现有有界方法。
- `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java`
  - 同时证明全量查询与有界查询具有不同且稳定的语义。

### Hierarchical 领域、规划与运行时

- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/ConversationTurn.java`
  - 删除 `completed`，只表达有序 user 轮次和消息范围。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalContextState.java`
  - 用统一 `turns` 和 `loadedThroughMessageSequence` 替代 `completedTurns/currentTurn`。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/RollingColdCompactionPlan.java`
  - 将输入字段中立化为加载水位，删除当前未完成轮次字段。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouper.java`
  - 只按 user 来源开启轮次，assistant 来源不再携带完成标记。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoader.java`
  - 校验 execution 会话归属后，调用全量消息查询并重建轮次。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlanner.java`
  - 直接按全部轮次计算 Cold 前缀和最近 10 轮保护后缀。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java`
  - 按 Cold 覆盖末轮过滤统一轮次列表，不再寻找或单独追加当前轮。
- `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinator.java`
  - 使用加载水位、统一轮次和固定保护数量生成并发布 Cold。

### Hierarchical 测试与迁移

- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouperTest.java`
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoaderTest.java`
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlannerTest.java`
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssemblerTest.java`
  - 新建，验证 Cold 覆盖与全量轮次输出顺序。
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinatorTest.java`
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/RollingColdSummaryGeneratorTest.java`
- `backend/agent-context-hierarchical/src/main/resources/db/agent-context-hierarchical/migration/V3__reset_legacy_turn_completion_context.sql`
  - 清理旧算法生成的 Hierarchical 派生数据。
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalDerivedDataResetMigrationTest.java`
  - 新建，证明从 V2 升级到 V3 只清理 Hierarchical 派生数据。
- `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalFlywayIntegrationTest.java`
  - 将 V3 纳入独立 Flyway 历史断言。

### 服务级回归与文档

- `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalFullHistoryIntegrationTest.java`
  - 新建，使用 cancelled/failed 空 assistant 历史证明最新 user 位于最终上下文末尾。
- `backend/agent-server/src/main/resources/application-business.yml`
  - 删除未被 Java 策略接收的 `recent-raw-turns` 配置声明。
- `README.md`
  - 删除无效环境变量条目，把说明改为固定保护最近 10 个 user 轮次。

---

### Task 1: 增加独立的会话全量消息查询

**Files:**
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java`

**Interfaces:**
- Consumes: `ConversationMessageEntity.conversationSessionId/role/conversationMessageSequence` 和现有租户、逻辑删除 MyBatis 拦截器。
- Produces: `List<ConversationMessage> ConversationMessageService.findByConversationSessionId(String conversationSessionId)`；原 `findByConversationSessionIdUpTo(String,long)` 保持原样。

- [ ] **Step 1: 扩展集成测试，同时描述全量与有界查询**

在 `ContentCenteredMessageIntegrationTest.submissionCreatesOneAssistantAndMessageQueriesUseTheCenterRows()` 中保留现有提交断言，并将会话查询部分改为：

```java
List<ConversationMessage> fullSessionMessages =
        TenantContext.call(
                TENANT_ID,
                () -> messages.findByConversationSessionId(conversationSessionId));
assertThat(fullSessionMessages)
        .extracting(ConversationMessage::getRole)
        .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);

List<ConversationMessage> boundedSessionMessages =
        TenantContext.call(
                TENANT_ID,
                () -> messages.findByConversationSessionIdUpTo(conversationSessionId, 1L));
assertThat(boundedSessionMessages)
        .extracting(ConversationMessage::getRole)
        .containsExactly(MessageRole.USER);
```

该测试同时防止实现者通过修改现有有界方法语义来影响 LSM。

- [ ] **Step 2: 运行测试并确认因新方法不存在而失败**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=ContentCenteredMessageIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: 测试编译失败，错误明确指出 `ConversationMessageService.findByConversationSessionId(String)` 不存在。

- [ ] **Step 3: 实现最小全量查询**

在 `ConversationMessageService` 中紧邻有界查询增加：

```java
/** 按会话读取全部真实 user/assistant 中心消息，供不使用消息上界的上下文策略重建历史。 */
public List<ConversationMessage> findByConversationSessionId(String conversationSessionId) {
    return toModels(
            lambdaQuery()
                    .eq(
                            ConversationMessageEntity::getConversationSessionId,
                            conversationSessionId)
                    .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                    .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                    .list());
}
```

不要让 `findByConversationSessionIdUpTo(...)` 委托给新方法后在内存中过滤；两个查询都保留数据库级边界和排序。

- [ ] **Step 4: 运行范围测试并确认通过**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=ContentCenteredMessageIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS；全量查询返回两条中心消息，有界查询在上界 1 时只返回 user。

- [ ] **Step 5: 提交公共查询能力**

```powershell
git add -- backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java
git commit -m "feat: 增加会话全量消息查询"
```

---

### Task 2: 将 Hierarchical 原子重构为统一有序轮次

**Files:**
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/ConversationTurn.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalContextState.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/RollingColdCompactionPlan.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouper.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoader.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlanner.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinator.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouperTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoaderTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/RollingColdCompactionPlannerTest.java`
- Create: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssemblerTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinatorTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/RollingColdSummaryGeneratorTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ConversationMessageService.findByConversationSessionId(String)`；现有 `ContextExecutionRef` 只用于 execution/session 一致性校验。
- Produces:
  - `ConversationTurn(long ordinal, long startMessageSequence, long endMessageSequence, List<ContextMessage> messages)`；
  - `HierarchicalContextState(..., List<ConversationTurn> turns, long loadedThroughMessageSequence, ...)`；
  - `RollingColdCompactionPlan(long loadedThroughMessageSequence, String sourceColdId, TurnRange targetCoverage, TurnRange rawSourceRange, TurnRange protectedRawRange, int historyMemoryBudget, List<MessageBatch> batches)`；
  - `RollingColdCompactionPlanner.plan(long, HierarchicalCold, List<ConversationTurn>, int recentRawTurns, int historyMemoryBudget, int summaryInputHardTokenBudget)`。

- [ ] **Step 1: 先把轮次测试改成不含完成状态的目标契约**

在 `ConversationTurnGrouperTest` 中删除所有 `completed()` 断言和 `SourceMessage.assistant(..., boolean, ...)` 参数，增加历史空 assistant 回归：

```java
@Test
void everyUserStartsANewTurnEvenWhenOlderAssistantsAreEmpty() {
    List<ConversationTurn> turns =
            grouper.groupSources(
                    List.of(
                            SourceMessage.user(1L, ContextMessage.user("cancelled question")),
                            SourceMessage.assistant(2L, List.of()),
                            SourceMessage.user(3L, ContextMessage.user("failed question")),
                            SourceMessage.assistant(4L, List.of()),
                            SourceMessage.user(5L, ContextMessage.user("latest question")),
                            SourceMessage.assistant(6L, List.of())));

    assertThat(turns).hasSize(3);
    assertThat(turns)
            .extracting(ConversationTurn::ordinal)
            .containsExactly(1L, 2L, 3L);
    assertThat(turns)
            .extracting(turn -> turn.messages().getFirst().content())
            .containsExactly("cancelled question", "failed question", "latest question");
    assertThat(turns.getLast().endMessageSequence()).isEqualTo(6L);
}
```

保留工具协议测试，但构造目标 `ConversationTurn` 时不再断言完成状态。

- [ ] **Step 2: 把 history loader 测试切换为全量查询**

在 `HierarchicalConversationHistoryLoaderTest` 中：

```java
when(messages.findByConversationSessionId("session"))
        .thenReturn(List.of(user, assistant));

List<ConversationTurn> turns =
        loader.load(new ContextExecutionRef("tenant", "session", "execution", 1L));

assertThat(turns).singleElement();
verify(messages).findByConversationSessionId("session");
verify(messages, never()).findByConversationSessionIdUpTo(anyString(), anyLong());
```

刻意把请求上界设为 1、返回消息序号包含 2，证明 Hierarchical 不再受该字段截断。保留 execution 不属于 session 时必须在读取历史前失败的测试。

- [ ] **Step 3: 把 planner 测试改成统一轮次和固定保护值**

将测试 helper 改为：

```java
private static List<ConversationTurn> turns(int count) {
    List<ConversationTurn> turns = new ArrayList<>();
    for (int ordinal = 1; ordinal <= count; ordinal++) {
        turns.add(
                new ConversationTurn(
                        ordinal,
                        ordinal * 2L - 1,
                        ordinal * 2L,
                        List.of(
                                ContextMessage.user("u" + ordinal),
                                ContextMessage.assistant("a" + ordinal, null))));
    }
    return turns;
}
```

所有 planner 调用显式传入策略唯一值：

```java
var plan = planner.plan(240L, null, turns(120), 10, 2_000, 4_096);
assertThat(plan.rawSourceRange()).isEqualTo(new TurnRange(1, 110));
assertThat(plan.protectedRawRange()).isEqualTo(new TurnRange(111, 120));
assertThat(plan.loadedThroughMessageSequence()).isEqualTo(240L);
```

再增加 10 轮和 11 轮边界：10 轮 `targetCoverage=EMPTY`；11 轮只覆盖第 1 轮。

- [ ] **Step 4: 新增 assembler 测试描述无 Cold 与有 Cold 的顺序**

创建 `HierarchicalConversationContextAssemblerTest`，使用 mock history/checkpoint/cold repository 和真实 estimator/validator/renderer。核心断言为：

```java
@Test
void keepsEveryUncoveredTurnInOrderWithoutSelectingACurrentTurn() {
    when(history.load(request.execution())).thenReturn(turns("old", "failed", "latest"));
    when(checkpoints.findCheckpoint("session")).thenReturn(checkpoint(null));

    HierarchicalContextState state = assembler.loadAndEstimate(request, policy);
    PreparedConversationContext prepared = assembler.prepareCurrentView(state);

    assertThat(state.loadedThroughMessageSequence()).isEqualTo(6L);
    assertThat(prepared.messages())
            .filteredOn(message -> message.role() == ContextMessage.Role.USER)
            .extracting(ContextMessage::content)
            .containsExactly("old", "failed", "latest");
}
```

第二个测试让 Cold 覆盖第 1 轮，断言渲染后的 memory 在前、原始输入只保留第 2～3 轮。测试 helper 创建的每轮使用连续 user/assistant 序号，`ContextBudget` 取足够大的硬上限，避免触发无关窗口失败。

- [ ] **Step 5: 运行修改后的测试并确认旧生产接口导致失败**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am '-Dtest=ConversationTurnGrouperTest,HierarchicalConversationHistoryLoaderTest,RollingColdCompactionPlannerTest,HierarchicalConversationContextAssemblerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: 编译或断言失败，原因包括旧 `completed` 字段、旧 assistant factory、旧有界查询或 assembler 缺少统一 `turns`。

- [ ] **Step 6: 收敛 `ConversationTurn` 与 grouper**

将 `ConversationTurn` 改为：

```java
/** 按真实 user 消息划分的有序原子轮次；assistant 正文是否存在不改变轮次身份。 */
public record ConversationTurn(
        long ordinal,
        long startMessageSequence,
        long endMessageSequence,
        List<ContextMessage> messages) {

    public ConversationTurn {
        if (ordinal <= 0L
                || startMessageSequence <= 0L
                || endMessageSequence < startMessageSequence) {
            throw new IllegalArgumentException("Invalid conversation turn boundary");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty() || messages.getFirst().role() != ContextMessage.Role.USER) {
            throw new IllegalArgumentException("Conversation turn must start with a user message");
        }
    }
}
```

`SourceMessage` 删除 `completedAssistant`，assistant factory 固定为：

```java
public static SourceMessage assistant(long sequence, List<ContextMessage> messages) {
    return new SourceMessage(sequence, SourceKind.ASSISTANT, messages);
}
```

`groupSources` 遇到 user 时提交上一轮并开启下一轮；assistant 只追加展开消息和推进 `endSequence`。删除 `completed` 局部变量和覆盖逻辑。

- [ ] **Step 7: 让 history loader 使用全量查询**

保留 execution 存在性和 session 归属校验，把读取改为：

```java
List<ConversationMessage> rows =
        messages.findByConversationSessionId(reference.conversationSessionId());
```

`validateRows` 删除 `row.sequence > reference.inputUpToMessageSequence()` 条件，仍校验会话、严格递增序号、role 和非空 content。调用 assistant factory 时只传 sequence 和 rebuilt messages。

- [ ] **Step 8: 收敛 state、plan 和 planner 接口**

`HierarchicalContextState` 改为：

```java
public record HierarchicalContextState(
        ConversationContextRequest request,
        HierarchicalContextPolicySnapshot policy,
        List<ConversationTurn> turns,
        long loadedThroughMessageSequence,
        HierarchicalCheckpoint checkpoint,
        int estimatedTokens) {

    public HierarchicalContextState {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (loadedThroughMessageSequence < 0L || estimatedTokens < 0) {
            throw new IllegalArgumentException("Invalid hierarchical context state");
        }
    }
}
```

`RollingColdCompactionPlan` 将首字段重命名为 `loadedThroughMessageSequence`，删除 `currentIncompleteTurn`。planner 使用：

```java
public RollingColdCompactionPlan plan(
        long loadedThroughMessageSequence,
        HierarchicalCold sourceCold,
        List<ConversationTurn> turns,
        int recentRawTurns,
        int historyMemoryBudget,
        int summaryInputHardTokenBudget) {
    List<ConversationTurn> frozen = List.copyOf(turns);
    if (recentRawTurns <= 0) {
        throw new IllegalArgumentException("recentRawTurns must be positive");
    }
    int protectedStartIndex = Math.max(0, frozen.size() - recentRawTurns);
    List<ConversationTurn> protectedTurns =
            frozen.subList(protectedStartIndex, frozen.size());
    long protectedStart = protectedTurns.isEmpty() ? 0L : protectedTurns.getFirst().ordinal();
    long compressibleEnd = protectedStart == 0L ? 0L : protectedStart - 1L;
    long sourceEnd = sourceCold == null ? 0L : sourceCold.coverage().endTurnSequence();
    long rawStart = sourceEnd + 1L;
    // 使用 frozen 而不是 completed 过滤结果计算 rawTurns 和批次。
}
```

返回计划时 `protectedRawRange` 使用保护后缀真实首末 ordinal，`targetCoverage=[1,compressibleEnd]`，`rawSourceRange=[sourceEnd+1,compressibleEnd]` 或 EMPTY。

- [ ] **Step 9: 收敛 assembler 为单一轮次序列**

`loadAndEstimate` 计算：

```java
List<ConversationTurn> turns = history.load(request.execution());
long loadedThrough = turns.isEmpty() ? 0L : turns.getLast().endMessageSequence();
HierarchicalContextState provisional =
        new HierarchicalContextState(
                request, policy, turns, loadedThrough, checkpoint, 0);
```

`dynamicMessages` 只保留：

```java
long finalCoveredThrough = coveredThrough;
state.turns().stream()
        .filter(turn -> turn.ordinal() > finalCoveredThrough)
        .forEach(turn -> messages.addAll(turn.messages()));
```

`historyMemoryBudget` 使用：

```java
int protectedStart =
        Math.max(0, state.turns().size() - state.policy().recentRawTurns());
state.turns()
        .subList(protectedStart, state.turns().size())
        .forEach(turn -> protectedMessages.addAll(turn.messages()));
```

删除所有 `completedTurns/currentTurn` 分支。`preparePublishedView` 在 checkpoint 版本替换分支中保留 `turns` 和 `loadedThroughMessageSequence`。

- [ ] **Step 10: 调整 coordinator 的计划、生成与 checkpoint 水位**

调用 planner：

```java
RollingColdCompactionPlan plan =
        planner.plan(
                state.loadedThroughMessageSequence(),
                input.sourceCold(),
                state.turns(),
                state.policy().recentRawTurns(),
                input.historyMemoryBudget(),
                input.summaryInputHardTokenBudget());
```

生成批次和首末轮全部从 `state.turns()` 按 ordinal 过滤/索引。checkpoint replacement 的 evaluated 水位使用：

```java
state.loadedThroughMessageSequence()
```

删除 `allTurns(state)` helper。更新 `HierarchicalCompactionCoordinatorTest` 的 state/plan 构造和 Mockito planner 签名，断言传入水位为 state 的 `loadedThroughMessageSequence`，而不是 request 的上界。

- [ ] **Step 11: 更新 summary generator 测试的构造器调用**

`RollingColdSummaryGenerator` 算法本身不变；仅将 `RollingColdSummaryGeneratorTest` 中所有：

```java
new ConversationTurn(ordinal, start, end, true, messages)
```

改为：

```java
new ConversationTurn(ordinal, start, end, messages)
```

不得借此修改摘要 prompt、schema、重试或模型身份规则。

- [ ] **Step 12: 运行 Hierarchical 模块测试**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

Expected: PASS；不再存在 `ConversationTurn::completed`、`completedTurns()`、`currentTurn()` 或 `currentIncompleteTurn()` 的编译引用。

- [ ] **Step 13: 扫描旧语义并提交核心重构**

Run:

```powershell
rg -n "completedTurns|currentTurn|currentIncompleteTurn|ConversationTurn::completed|findFirst\(\)" backend/agent-context-hierarchical/src
```

Expected: 无匹配。随后提交：

```powershell
git add -- backend/agent-context-hierarchical
git commit -m "fix: 按有序轮次组装 Hierarchical 上下文"
```

---

### Task 3: 失效旧 Hierarchical 派生摘要

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/resources/db/agent-context-hierarchical/migration/V3__reset_legacy_turn_completion_context.sql`
- Create: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalDerivedDataResetMigrationTest.java`
- Modify: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalFlywayIntegrationTest.java`

**Interfaces:**
- Consumes: Hierarchical 独立 Flyway history `flyway_agent_context_hierarchical_schema_history`，V2 结构中的 checkpoint/Cold 表。
- Produces: V3 数据迁移；升级后两个 Hierarchical 派生表为空，原始会话和 LSM 数据不变。

- [ ] **Step 1: 写 V2 到 V3 的迁移行为测试**

创建 `HierarchicalDerivedDataResetMigrationTest`，使用独立 PostgreSQLContainer。测试顺序必须明确：

```java
@Test
void v3ClearsOnlyLegacyHierarchicalDerivedData() {
    migratePrimary();
    migrateHierarchicalToVersion("2");
    insertTenantSessionColdAndCheckpoint();

    assertThat(count("conversation_context_hierarchical_checkpoint")).isEqualTo(1);
    assertThat(count("conversation_context_hierarchical_cold")).isEqualTo(1);
    assertThat(count("conversation_session")).isEqualTo(1);

    migrateHierarchicalToLatest();

    assertThat(count("conversation_context_hierarchical_checkpoint")).isZero();
    assertThat(count("conversation_context_hierarchical_cold")).isZero();
    assertThat(count("conversation_session")).isEqualTo(1);
}
```

测试 fixture 使用以下最小数据：

```sql
insert into tenant (tenant_id, name) values ('tenant_reset', 'Reset Test');
insert into conversation_session (
    conversation_session_id, tenant_id, title
) values ('session_reset', 'tenant_reset', 'Reset Test');
insert into conversation_context_hierarchical_cold (
    hierarchical_cold_id, tenant_id, conversation_session_id,
    start_turn_sequence, end_turn_sequence,
    start_message_sequence, end_message_sequence,
    summary_document, persisted_token_count, rendered_token_count,
    target_rendered_tokens, model_provider, model_name,
    source_content_sha256, created_at, created_by, updated_at, updated_by
) values (
    'cold_reset', 'tenant_reset', 'session_reset',
    1, 1, 1, 2,
    '{}'::jsonb, 1, 1, 1, 'test', 'test',
    repeat('0', 64), localtimestamp, 'test', localtimestamp, 'test'
);
insert into conversation_context_hierarchical_checkpoint (
    hierarchical_checkpoint_id, tenant_id, conversation_session_id,
    active_cold_id, evaluated_up_to_conversation_message_sequence,
    strategy_generation, created_at, created_by, updated_at, updated_by
) values (
    'checkpoint_reset', 'tenant_reset', 'session_reset',
    'cold_reset', 2, 1, localtimestamp, 'test', localtimestamp, 'test'
);
```

Flyway helper 对 Hierarchical V2 使用 `target(MigrationVersion.fromVersion("2"))`，latest 调用不设置 target；两次使用相同独立 history table。

- [ ] **Step 2: 运行迁移测试并确认因为 V3 不存在而失败**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am '-Dtest=HierarchicalDerivedDataResetMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: 最后两个派生表计数仍为 1，断言失败。

- [ ] **Step 3: 增加只清理派生数据的 V3 SQL**

文件内容固定为：

```sql
-- 旧算法会跳过空 assistant 轮次，其 Cold 覆盖与新 user 边界算法不兼容。
-- checkpoint 和 Cold 均为可由原始会话消息重建的派生数据；先删发布指针，再删摘要正文。
delete from conversation_context_hierarchical_checkpoint;
delete from conversation_context_hierarchical_cold;
```

不得删除、更新或 truncate 主会话表及 LSM 表。

- [ ] **Step 4: 把 V3 纳入 Flyway 历史断言**

在 `HierarchicalFlywayIntegrationTest` 增加：

```java
private static final String RESET_LEGACY_CONTEXT_SCRIPT =
        "V3__reset_legacy_turn_completion_context.sql";
```

并将 expected migration 顺序更新为 baseline、V1、V2、V3；原表结构和外键断言保持不变。

- [ ] **Step 5: 运行全部 Hierarchical 持久化测试**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am '-Dtest=*Hierarchical*MigrationTest,HierarchicalFlywayIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS；V3 后派生表为空且 session 保留，独立 Flyway history 顺序正确。

- [ ] **Step 6: 提交迁移**

```powershell
git add -- backend/agent-context-hierarchical/src/main/resources/db/agent-context-hierarchical/migration/V3__reset_legacy_turn_completion_context.sql backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence
git commit -m "chore: 重置旧 Hierarchical 派生摘要"
```

---

### Task 4: 增加故障 session 服务级回归

**Files:**
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalFullHistoryIntegrationTest.java`

**Interfaces:**
- Consumes: 实际 Spring Hierarchical auto-configuration、PostgreSQL 主/插件迁移、Task 1 全量查询、Task 2 策略实现。
- Produces: 一个不调用正常回答模型的服务级回归，直接断言 `PreparedConversationContext` 的最终中立消息顺序。

- [ ] **Step 1: 创建集成测试并构造两个空 assistant 历史轮次**

测试类使用：

```java
@SpringBootTest(properties = "data-agent.context.strategy=hierarchical")
class HierarchicalFullHistoryIntegrationTest {
    @Autowired private HierarchicalConversationContextStrategy strategy;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ContextSummaryModelPort summaryModel;
}
```

使用 PostgreSQLContainer 和 `@DynamicPropertySource`，与现有 server 集成测试一致。`@BeforeEach`
按以下顺序清理 fixture，显式打断 message/execution 的循环外键：

```java
jdbc.update("delete from agent_execution_event");
jdbc.update("delete from agent_execution_stream_checkpoint");
jdbc.update("delete from conversation_context_hierarchical_checkpoint");
jdbc.update("delete from conversation_context_hierarchical_cold");
jdbc.update("update conversation_message set execution_id = null");
jdbc.update("delete from agent_execution");
jdbc.update("delete from conversation_message");
jdbc.update("delete from conversation_session");
jdbc.update("delete from tenant where tenant_id = 'tenant_local'");
```

随后插入 `tenant_local` 和一个 session。

插入三次 execution，每次遵循真实循环外键顺序：先插 user（`execution_id=null`），再插 execution（`triggering_conversation_message_id=userId`），回填 user.execution_id，最后插 assistant。状态和内容为：

```text
execution_cancelled: user="cancelled question", assistant="", status=cancelled
execution_failed:    user="failed question",    assistant="", status=failed
execution_running:   user="latest question",    assistant="", status=running
```

为每个 execution 使用不同幂等键、64 位 payload hash 和未来 hard deadline；会话消息序号为 1～6，会话水位更新为 6。

- [ ] **Step 2: 调用真实 Hierarchical 策略并断言最终顺序**

让摘要模型 identity 可用但不触发 summarize：

```java
when(summaryModel.identity())
        .thenReturn(new ContextSummaryModelIdentity("test", "test"));
```

使用足够大的预算并在租户上下文中调用：

```java
ConversationContextRequest request =
        new ConversationContextRequest(
                new ContextExecutionRef(
                        "tenant_local", "session_history", "execution_running", 6L),
                new ContextBudget(100_000, 1_000, 1_000, 0),
                List.of(),
                List.of());

PreparedConversationContext prepared =
        TenantContext.call("tenant_local", () -> strategy.prepare(request));

assertThat(prepared.messages())
        .filteredOn(message -> message.role() == ContextMessage.Role.USER)
        .extracting(ContextMessage::content)
        .containsExactly("cancelled question", "failed question", "latest question");
assertThat(prepared.messages().getLast().role()).isEqualTo(ContextMessage.Role.USER);
assertThat(prepared.messages().getLast().content()).isEqualTo("latest question");
verify(summaryModel, never()).summarize(any());
```

再断言 `prepared.messages()` 中不存在空正文 assistant 消息。

- [ ] **Step 3: 运行回归测试**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=HierarchicalFullHistoryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS；旧 cancelled/failed user 均保留，最新 user 位于输入末尾，未调用摘要模型。

- [ ] **Step 4: 运行同会话活动冲突测试，确认关键前提仍成立**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=AgentExecutionSubmissionIntegrationTest#activeExecutionConflictsWithAnotherSubmission' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS；第二次提交返回冲突且没有新增消息。

- [ ] **Step 5: 提交服务级回归**

```powershell
git add -- backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalFullHistoryIntegrationTest.java
git commit -m "test: 覆盖 Hierarchical 空回答历史"
```

---

### Task 5: 清理无效配置、执行 LSM 隔离验证与完整验收

**Files:**
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: `HierarchicalContextPolicySnapshot.recentRawTurns()` 的固定值 10。
- Produces: 不再宣传无效的 `DATA_AGENT_CONTEXT_HIERARCHICAL_RECENT_RAW_TURNS` 配置；README 准确说明 Hierarchical 固定保护最后 10 个 user 轮次。

- [ ] **Step 1: 写出当前配置残留扫描并确认存在**

Run:

```powershell
rg -n "DATA_AGENT_CONTEXT_HIERARCHICAL_RECENT_RAW_TURNS|recent-raw-turns|最近完整对话轮数" backend/agent-server/src/main/resources/application-business.yml README.md
```

Expected: 至少命中 application-business.yml 和 README 中的旧配置声明。

- [ ] **Step 2: 删除失效配置并更新 README**

从 `application-business.yml` 删除：

```yaml
recent-raw-turns: ${DATA_AGENT_CONTEXT_HIERARCHICAL_RECENT_RAW_TURNS:10}
```

从 README 配置表删除 `DATA_AGENT_CONTEXT_HIERARCHICAL_RECENT_RAW_TURNS` 行。将 Hierarchical 描述改为：

```text
Hierarchical 固定原样保护按 user 消息划分的最后 10 个轮次；更早的连续前缀按需滚入 Cold 摘要。失败或取消留下的空 assistant 不改变轮次边界。
```

不要修改 LSM 配置项或 75%/65% 行为说明。

- [ ] **Step 3: 验证无效配置已消失且固定值只有一个 Java 事实源**

Run:

```powershell
rg -n "DATA_AGENT_CONTEXT_HIERARCHICAL_RECENT_RAW_TURNS|recent-raw-turns" backend README.md
rg -n "RECENT_RAW_TURNS\s*=\s*10" backend/agent-context-hierarchical/src/main/java
```

Expected: 第一条无匹配；第二条只命中 `HierarchicalContextPolicySnapshot` 一处。

- [ ] **Step 4: 运行 Hierarchical 范围验证**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical,agent-server -am '-Dtest=*Hierarchical*Test,ContentCenteredMessageIntegrationTest,AgentExecutionSubmissionIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS。

- [ ] **Step 5: 运行 LSM 全量回归并确认生产目录零改动**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am test
git diff 56bede3 -- backend/agent-context-lsm/src/main
```

Expected: LSM 模块测试 PASS；diff 无输出。

- [ ] **Step 6: 运行后端完整验证**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

Expected: 所有模块 `BUILD SUCCESS`。

- [ ] **Step 7: 检查最终差异只包含批准范围**

Run:

```powershell
git status --short
git diff --check
git diff --stat 56bede3
rg -n "completedTurns|currentIncompleteTurn|ConversationTurn::completed" backend/agent-context-hierarchical/src
```

Expected:

- 只有 Task 1～5 指定文件发生变化；
- `git diff --check` 无输出；
- 旧 Hierarchical 完成状态语义扫描无匹配；
- `backend/agent-context-lsm/src/main` 没有变化。

- [ ] **Step 8: 提交配置和文档收尾**

```powershell
git add -- backend/agent-server/src/main/resources/application-business.yml README.md
git commit -m "docs: 明确 Hierarchical 最近轮次策略"
```

- [ ] **Step 9: 记录最终证据**

在交付消息中报告：

```text
- Hierarchical 模块测试命令与 BUILD SUCCESS
- 故障 session 回归测试命令与 PASS
- LSM 模块回归命令与 BUILD SUCCESS
- 后端完整 verify 与 BUILD SUCCESS
- 最终提交列表和工作树状态
```

不要仅报告“测试已通过”，必须给出实际执行命令和结果。
