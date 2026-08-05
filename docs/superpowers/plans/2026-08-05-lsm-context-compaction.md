# LSM Context Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Data Agent V2 增加一个可拔除的 `agent-context-lsm` 插件，在完整模型输入超过上下文窗口 75% 时，以可配置 fanout/maxLevel 的 LSM 摘要结构压缩历史会话，同时保留最近 10 轮原文和稳定的系统框架。

**Architecture:** `agent-domain` 只提供模型无关的上下文策略 SPI；`agent-web` 提供 raw 策略、策略解析器、execution control 与 Ollama 摘要模型适配器；新的 `agent-context-lsm` 模块独立承载 LSM 算法、专属 MyBatis 持久化、独立 Flyway history、事务恢复和指标。`agent-server` 仅通过依赖和 `data-agent.context.strategy` 选择是否装入插件，ReAct 引擎不引用 LSM 实现类。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring AI 2.0、MyBatis-Plus 3.5.16、PostgreSQL、Flyway、Jackson 3、NetworkNT JSON Schema Validator、Micrometer、JUnit 5、AssertJ、Mockito、Testcontainers。

## Global Constraints

- 只在当前 `data-agent-v2` 工作区内 inline execution；仓库规则禁止 subagent 和额外 Git worktree，因此执行本计划时使用 `superpowers:executing-plans`。
- 每个任务完成并验证后创建一个独立 commit；不得合并或跳过计划中的 commit gate。
- LSM 专属代码、Entity、Mapper、Repository、JSON codec 和 migration 全部位于 `backend/agent-context-lsm`；不得放入 `agent-persistence`。
- `agent-domain` 的 SPI 不得出现 LSM level、segment、manifest 或 compaction job 类型。
- `agent-context-lsm` 不得依赖 `agent-web`、`agent-server` 或具体模型供应商。
- 固定 system prompt、tool definitions 和协议开销参与 token 预算，但永不进入摘要。
- `fanout` 默认 5、合法范围 3～10；`maxLevel` 默认 4、合法范围 1～8。
- `baseSegmentTurns=10`、触发水位 75%、目标水位 65%、Warm 发布后保持 100～109 轮、Hot 保护最近 10 个完整问答。
- 原始 message 与 `thinkingProcess` 不修改、不删除；摘要正文、原文、tool payload 和 system prompt 不进入日志、事件或指标标签。
- 普通层最多 `fanout-1` 个活动节点，配置顶层只有一个活动 ROOT；所有活动覆盖必须连续、无重叠、无缺口。
- 新增字段和逻辑代码块按仓库规则添加中文用途注释；字符串常量集中定义，配置 key、默认值和 errorMessage 除外。
- 先运行任务内最小测试，再运行受影响模块测试；最终运行 `backend` 完整 `mvn verify`。

---

## File Map

### Existing modules

- `backend/agent-domain/.../conversation/context/*`：模型无关 SPI、请求/响应和 execution control 端口。
- `backend/agent-web/.../conversation/context/*`：策略配置、解析器、raw 策略和领域消息到 `ModelInputMessage` 的映射。
- `backend/agent-web/.../conversation/model/OllamaContextSummaryModelAdapter.java`：唯一 Ollama 摘要调用适配器。
- `backend/agent-web/.../conversation/react/ReactExecutionEngine.java`：把直接 loader 调用替换成 SPI 调用。
- `backend/agent-server/pom.xml` 与 `application-business.yml`：运行时选择插件和策略。

### New plugin module

- `backend/agent-context-lsm/.../configuration/*`：条件装配、配置校验、专属 mapper scan 和独立 Flyway initializer。
- `backend/agent-context-lsm/.../domain/*`：插件内部 segment、manifest、job、plan 和 summary document 类型。
- `backend/agent-context-lsm/.../persistence/*`：插件专属 Entity、Mapper、Repository 和 JSON codec。
- `backend/agent-context-lsm/.../planning/*`：turn grouping、token estimator、size-tiered planner 和 frontier selector。
- `backend/agent-context-lsm/.../summary/*`：摘要 prompt、schema 校验、幂等 key 和两次生成尝试。
- `backend/agent-context-lsm/.../runtime/*`：compaction job、事务边界、manifest 发布、恢复和策略入口。
- `backend/agent-context-lsm/src/main/resources/db/agent-context-lsm/migration/*`：独立 Flyway migration。
- `backend/agent-context-lsm/src/main/resources/schema/context-summary-v1.schema.json`：摘要文档 schema。

---

### Task 1: 定义模型无关的上下文 SPI

**Files:**
- Modify: `backend/agent-domain/pom.xml`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextStrategy.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextRequest.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/PreparedConversationContext.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextMessage.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextToolCall.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextToolDefinition.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextBudget.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextPolicySnapshot.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextExecutionRef.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextSummaryModelPort.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextSummaryRequest.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextSummaryResult.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextExecutionControlPort.java`
- Test: `backend/agent-domain/src/test/java/com/datagent/agentv2/conversation/context/ContextContractsTest.java`

**Interfaces:**
- Consumes: existing conversation business identifiers and Java collections only.
- Produces: `ConversationContextStrategy#prepare(ConversationContextRequest)`, `ContextSummaryModelPort#summarize(ContextSummaryRequest)` and `ContextExecutionControlPort` used by all later tasks.

- [ ] **Step 1: Add the domain test dependency and write failing contract tests**

Add `spring-boot-starter-test` with test scope to `agent-domain/pom.xml`. Test constructor validation, immutable copies, budget math, and the absence of LSM vocabulary:

```java
@Test
void budgetUsesWholeWindowForSoftThresholdAndReservesOutputForHardLimit() {
    ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 8_000);

    assertThat(budget.softTriggerTokens()).isEqualTo(49_152);
    assertThat(budget.targetInputTokens()).isEqualTo(42_598);
    assertThat(budget.hardInputLimitTokens()).isEqualTo(57_344);
}

@Test
void preparedContextDefensivelyCopiesMessages() {
    List<ContextMessage> messages = new ArrayList<>();
    PreparedConversationContext prepared =
            new PreparedConversationContext("raw", messages, 0, false, 0);
    messages.add(ContextMessage.user("late mutation"));

    assertThat(prepared.messages()).isEmpty();
}
```

- [ ] **Step 2: Run the domain tests and confirm the contracts do not exist**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain -Dtest=ContextContractsTest test
```

Expected: FAIL during test compilation because `ContextBudget` and related contracts are missing.

- [ ] **Step 3: Implement the minimal neutral contracts**

Use these exact public signatures:

```java
public interface ConversationContextStrategy {
    String strategyId();
    PreparedConversationContext prepare(ConversationContextRequest request);
}

public record ConversationContextRequest(
        ContextExecutionRef execution,
        ContextBudget budget,
        ContextPolicySnapshot policy,
        List<ContextMessage> fixedSystemMessages,
        List<ContextToolDefinition> toolDefinitions) {
    public ConversationContextRequest withPolicy(ContextPolicySnapshot replacement) {
        return new ConversationContextRequest(
                execution, budget, replacement, fixedSystemMessages, toolDefinitions);
    }
}

public record PreparedConversationContext(
        String strategyId,
        List<ContextMessage> messages,
        int estimatedInputTokens,
        boolean compacted,
        long manifestGeneration) {}

public record ContextBudget(
        int contextWindowTokens,
        int maxOutputTokens,
        int safetyReserveTokens,
        int fixedInputTokens) {
    public int softTriggerTokens() { return (int) Math.floor(contextWindowTokens * 0.75d); }
    public int targetInputTokens() { return (int) Math.floor(contextWindowTokens * 0.65d); }
    public int hardInputLimitTokens() {
        return contextWindowTokens - maxOutputTokens - safetyReserveTokens;
    }
}

public record ContextExecutionRef(
        String tenantId,
        String conversationSessionId,
        String executionId,
        String ownerInstanceId,
        long executionAttempt,
        long inputUpToMessageSequence) {}

public record ContextPolicySnapshot(
        int policyVersion,
        int fanout,
        int maxLevel,
        int baseSegmentTurns,
        int compressionThresholdPercent,
        int targetWatermarkPercent,
        int warmTargetTurns,
        int warmMaxTurns,
        int protectedHotTurns) {}

public record ContextMessage(
        Role role,
        String content,
        String thinking,
        List<ContextToolCall> toolCalls,
        String toolCallId,
        String toolName) {}
```

`ContextMessage` carries role, content, optional historical thinking, tool calls, toolCallId and toolName. Use `Map<String, Object>` in `ContextToolCall` and `ContextToolDefinition` so `agent-domain` does not depend on Jackson.

Define summary and execution ports without LSM types:

```java
public interface ContextSummaryModelPort {
    ContextSummaryResult summarize(ContextSummaryRequest request);
}

public interface ContextExecutionControlPort {
    void assertOwnedAndWithinBudget(ContextExecutionRef execution);
    boolean cancelIfRequested(ContextExecutionRef execution);
    void publish(ContextExecutionRef execution, ContextLifecycleEvent event);
    void fail(ContextExecutionRef execution, String failureCode, String safeMessage);
}
```

Create `ContextLifecycleEvent` as a nested record in the port with `type`, `summaryLevel`, range, estimated tokens and status only; do not add message or summary content.

- [ ] **Step 4: Run formatting and domain tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain spotless:apply test
```

Expected: PASS.

- [ ] **Step 5: Commit the domain SPI**

```powershell
git add backend/agent-domain
git commit -m "feat: define conversation context SPI"
```

---

### Task 2: 接入 raw 策略和策略解析器

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ContextStrategyProperties.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextStrategyResolver.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/RawConversationContextStrategy.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ContextModelMessageMapper.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ConversationContextLoader.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/context/ConversationContextStrategyResolverTest.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ConversationContextLoaderIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `ConversationContextStrategy`, request/response records and existing `ConversationContextLoader#load`.
- Produces: `ConversationContextStrategyResolver#prepare(ConversationContextRequest)` and `raw` strategy; ReAct integration consumes these in Task 9.

- [ ] **Step 1: Write resolver and raw-strategy tests**

```java
@Test
void selectsExactlyConfiguredStrategy() {
    ConversationContextStrategy raw = strategy("raw");
    ConversationContextStrategy lsm = strategy("lsm");
    var resolver = new ConversationContextStrategyResolver(List.of(raw, lsm), properties("lsm"));

    assertThat(resolver.selected()).isSameAs(lsm);
}

@Test
void rejectsUnknownOrDuplicateStrategyIds() {
    assertThatThrownBy(() -> resolver(List.of(strategy("raw")), "lsm"))
            .hasMessageContaining("lsm");
    assertThatThrownBy(() -> resolver(List.of(strategy("raw"), strategy("raw")), "raw"))
            .hasMessageContaining("Duplicate");
}
```

Extend the existing loader integration test so `RawConversationContextStrategy.prepare` returns the same ordered user/assistant/tool protocol messages as `load`.

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web,agent-server -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=ConversationContextStrategyResolverTest,ConversationContextLoaderIntegrationTest test
```

Expected: FAIL because resolver and raw strategy classes do not exist.

- [ ] **Step 3: Implement resolver, properties, raw adapter and mapper**

Use:

```java
@ConfigurationProperties("data-agent.context")
public class ContextStrategyProperties {
    private String strategy = "raw";
    // validated getter/setter
}

public final class ConversationContextStrategyResolver {
    public ConversationContextStrategy selected();
    public PreparedConversationContext prepare(ConversationContextRequest request);
}
```

`RawConversationContextStrategy` delegates to the existing loader, maps `ModelInputMessage` to neutral `ContextMessage`, returns `manifestGeneration=0`, `compacted=false`, and never calls the summary port. Keep the loader itself focused on reconstructing persisted messages and tool protocol.

- [ ] **Step 4: Run focused and module tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web,agent-server -am spotless:apply test
```

Expected: PASS with existing raw behavior unchanged.

- [ ] **Step 5: Commit the strategy seam**

```powershell
git add backend/agent-web backend/agent-server/src/test
git commit -m "feat: add pluggable context strategy resolver"
```

---

### Task 3: 创建独立 LSM 插件模块和条件装配

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/agent-server/pom.xml`
- Create: `backend/agent-context-lsm/pom.xml`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextAutoConfiguration.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextProperties.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmMapperConfiguration.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmFlywayMigrator.java`
- Create: `backend/agent-context-lsm/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/configuration/LsmContextAutoConfigurationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/server/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: Task 1 SPI and Task 2 configured strategy ID.
- Produces: conditional plugin auto-configuration, validated `LsmContextProperties`, plugin mapper scan, and an ordered dedicated Flyway hook used by Task 4.

- [ ] **Step 1: Write module and auto-configuration tests first**

Use `ApplicationContextRunner` to assert:

```java
@Test
void rawDoesNotCreateLsmBeansOrRunPluginMigration() {
    contextRunner.withPropertyValues("data-agent.context.strategy=raw")
            .run(context -> assertThat(context).doesNotHaveBean(LsmContextProperties.class));
}

@Test
void lsmRejectsFanoutBelowThreeAndMaxLevelAboveEight() {
    contextRunner.withPropertyValues(
                    "data-agent.context.strategy=lsm",
                    "data-agent.context.compaction.fanout=2")
            .run(context -> assertThat(context).hasFailed());
}
```

Add a module-boundary assertion that classes under `com.datagent.agentv2.context.lsm` do not reference packages containing `.conversation.react.`, `.conversation.model.` or `.server.`.

- [ ] **Step 2: Run the new module test and verify reactor failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am test
```

Expected: FAIL because the module is not registered.

- [ ] **Step 3: Scaffold the module and conditional auto-configuration**

Register `agent-context-lsm` after `agent-persistence` in the reactor. Declare direct dependencies on `agent-domain`, `agent-persistence`, JDBC, Flyway, PostgreSQL runtime, MyBatis-Plus, Jackson, validation, Micrometer, JSON Schema Validator and test dependencies. Do not add an `agent-web` dependency.

Use exact property fields:

```java
@ConfigurationProperties("data-agent.context.compaction")
@Validated
public class LsmContextProperties {
    @Min(3) @Max(10) private int fanout = 5;
    @Min(1) @Max(8) private int maxLevel = 4;
}
```

`LsmContextAutoConfiguration` must use:

```java
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "lsm")
@EnableConfigurationProperties(LsmContextProperties.class)
@Import({LsmMapperConfiguration.class, LsmFlywayMigrator.class})
public class LsmContextAutoConfiguration {}
```

The migrator must construct a dedicated Flyway with location `classpath:db/agent-context-lsm/migration` and table `flyway_agent_context_lsm_schema_history`, and run after the primary `flywayInitializer`.

- [ ] **Step 4: Run plugin and module-boundary tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-server -am spotless:apply test
```

Expected: PASS; raw context does not initialize the plugin.

- [ ] **Step 5: Commit the plugin shell**

```powershell
git add backend/pom.xml backend/agent-context-lsm backend/agent-server/pom.xml backend/agent-server/src/test
git commit -m "feat: scaffold LSM context plugin"
```

---

### Task 4: 实现插件专属数据库模型

**Files:**
- Create: `backend/agent-context-lsm/src/main/resources/db/agent-context-lsm/migration/V1__create_lsm_context_store.sql`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/entity/LsmSummarySegmentEntity.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/entity/LsmContextManifestEntity.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/entity/LsmCompactionJobEntity.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/entity/LsmExecutionPolicyEntity.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmSummarySegmentMapper.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmContextManifestMapper.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmCompactionJobMapper.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmExecutionPolicyMapper.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmSummarySegmentRepository.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmContextManifestRepository.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmCompactionJobRepository.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmExecutionPolicyRepository.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/json/LsmDocumentCodec.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/persistence/LsmFlywayIntegrationTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/persistence/LsmRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: plugin Flyway/mapper configuration from Task 3 and existing `TenantAuditedEntity`, `TenantContext`, JSONB handlers and ID generator.
- Produces: immutable segment store, one-row-per-session manifest CAS, recoverable job store and one-row-per-execution policy snapshot.

- [ ] **Step 1: Write migration and repository integration tests**

The migration test must run the primary Flyway first and plugin Flyway second against PostgreSQL Testcontainers, then assert these tables and separate histories exist:

```java
assertThat(tables()).contains(
        "conversation_context_lsm_segment",
        "conversation_context_lsm_manifest",
        "conversation_context_lsm_job",
        "conversation_context_lsm_execution_policy",
        "flyway_agent_context_lsm_schema_history");
assertThat(primaryFlywayHistory()).doesNotContain("V1__create_lsm_context_store.sql");
```

Repository tests must cover tenant isolation, segment-key idempotency, one manifest per tenant/session and stale-version CAS rejection.

- [ ] **Step 2: Run persistence tests and verify missing schema**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=LsmFlywayIntegrationTest,LsmRepositoryIntegrationTest test
```

Expected: FAIL because plugin tables and repositories do not exist.

- [ ] **Step 3: Add the migration with explicit constraints and comments**

Create four tenant-scoped tables. The SQL must include at least these stable constraints:

```sql
create table conversation_context_lsm_segment (
    id bigint generated always as identity primary key,
    summary_segment_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    segment_key char(64) not null,
    kind varchar(16) not null check (kind in ('STANDARD', 'ROOT')),
    level integer not null check (level >= 0),
    start_turn_sequence bigint not null,
    end_turn_sequence bigint not null,
    start_message_sequence bigint not null,
    end_message_sequence bigint not null,
    partial_segment boolean not null,
    summary_document jsonb not null,
    estimated_token_count integer not null check (estimated_token_count >= 0),
    source_segment_ids jsonb not null,
    source_content_sha256 char(64) not null,
    summary_schema_version integer not null,
    prompt_version integer not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    creation_policy jsonb not null,
    source_segment_count integer not null check (source_segment_count >= 0),
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_segment_key unique (tenant_id, conversation_session_id, segment_key),
    constraint ck_context_lsm_segment_range check (
        start_turn_sequence <= end_turn_sequence
        and start_message_sequence <= end_message_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);
```

Use these exact remaining business columns and constraints (follow the same audit/deleted/version columns as the segment table):

```sql
create table conversation_context_lsm_manifest (
    id bigint generated always as identity primary key,
    context_manifest_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    active_root_segment_id varchar(64),
    active_segment_ids jsonb not null default '[]'::jsonb,
    pending_raw_start_turn_sequence bigint,
    pending_raw_end_turn_sequence bigint,
    warm_cutoff_turn_sequence bigint not null default 0,
    hot_from_turn_sequence bigint not null default 0,
    evaluated_up_to_message_sequence bigint not null default 0,
    effective_policy_version integer not null,
    effective_fanout integer not null,
    effective_max_level integer not null,
    generation bigint not null default 0 check (generation >= 0),
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_manifest_session unique (tenant_id, conversation_session_id),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_lsm_job (
    id bigint generated always as identity primary key,
    compaction_job_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    execution_attempt bigint not null,
    input_up_to_message_sequence bigint not null,
    source_manifest_generation bigint not null,
    source_manifest_version bigint not null,
    policy_snapshot jsonb not null,
    plan_document jsonb not null,
    plan_sha256 char(64) not null,
    status varchar(16) not null,
    owner_instance_id varchar(128) not null,
    failure_code varchar(64),
    completed_at timestamp,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_job_boundary unique (
        tenant_id, execution_id, execution_attempt, input_up_to_message_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_lsm_execution_policy (
    id bigint generated always as identity primary key,
    execution_policy_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    policy_snapshot jsonb not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_execution_policy unique (tenant_id, execution_id),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);
```

Create manifest, job and execution-policy tables with the fields defined in the approved spec, JSONB documents, non-negative counters and composite session foreign keys. The policy table has unique `(tenant_id, execution_id)` and stores the immutable strategy snapshot before any 75% decision. Add `comment on column` for every field.

- [ ] **Step 4: Implement entities, codecs and repositories**

Repositories encapsulate all Mapper usage. Expose these exact operations:

```java
Optional<LsmSummarySegmentEntity> findBySegmentKey(String sessionId, String segmentKey);
List<LsmSummarySegmentEntity> findByIdsInOrder(List<String> segmentIds);
Optional<LsmContextManifestEntity> findManifest(String sessionId);
boolean compareAndSetManifest(
        String sessionId, long expectedVersion, long expectedGeneration,
        LsmContextManifestEntity replacement);
Optional<LsmCompactionJobEntity> findRecoverableJob(
        String executionId, long executionAttempt, long inputBoundary);
ContextPolicySnapshot findOrCreateExecutionPolicy(
        ContextExecutionRef execution, ContextPolicySnapshot configuredPolicy);
```

Use plugin package-local Mapper injection only; no application service outside `.persistence.` may depend on `BaseMapper`.

- [ ] **Step 5: Run plugin persistence tests and commit**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am spotless:apply test
git add backend/agent-context-lsm
git commit -m "feat: persist LSM context state"
```

---

### Task 5: 实现问答分组、策略快照和 token 估算

**Files:**
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/ConversationTurn.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/ConversationTurnGrouper.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmContextPolicy.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/ConversationTokenEstimator.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/ConversationTurnGrouperTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/ConversationTokenEstimatorTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmContextPolicyTest.java`

**Interfaces:**
- Consumes: Task 1 context messages/budget/policy and persisted `ConversationMessage`/`thinkingProcess` documents.
- Produces: ordered complete/current turns, exact soft/target/hard thresholds and deterministic estimates for Task 6/7.

- [ ] **Step 1: Write failing grouping and boundary tests**

Cover completed turns, retry sharing one triggering user message, tool atomicity, current unfinished turn, CJK/Latin/JSON and threshold edges:

```java
@Test
void groupsRetryProcessesIntoOneTurnAndLeavesCurrentTurnUnfinished() {
    List<ConversationTurn> turns = grouper.group(historyWithRetryAndCurrentQuestion());

    assertThat(turns).hasSize(2);
    assertThat(turns.get(0).completed()).isTrue();
    assertThat(turns.get(0).messages()).extracting(ContextMessage::role)
            .containsExactly(USER, ASSISTANT, TOOL, ASSISTANT);
    assertThat(turns.get(1).completed()).isFalse();
}

@Test
void triggersOnlyAboveSeventyFivePercent() {
    assertThat(policy.requiresCompaction(49_152, budget())).isFalse();
    assertThat(policy.requiresCompaction(49_153, budget())).isTrue();
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=ConversationTurnGrouperTest,ConversationTokenEstimatorTest,LsmContextPolicyTest test
```

Expected: FAIL because planning classes are absent.

- [ ] **Step 3: Implement grouping and conservative estimation**

`ConversationTokenEstimator` must expose:

```java
int estimateMessages(List<ContextMessage> messages);
int estimateTools(List<ContextToolDefinition> tools);
int estimateSummaryDocument(String jsonDocument);
int estimateCompleteInput(ConversationContextRequest request, List<ContextMessage> messages);
```

Count each CJK code point as at least one token, Latin text conservatively by code points, and add constants for role boundaries, tool call IDs, JSON punctuation and message delimiters. Never consume the safety reserve to admit more business input.

- [ ] **Step 4: Run property-style deterministic loops and plugin tests**

Add a fixed-seed loop that creates 10,000 mixed turns and asserts estimates are monotonic when content is appended. Then run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am spotless:apply test
```

Expected: PASS.

- [ ] **Step 5: Commit the input policy foundation**

```powershell
git add backend/agent-context-lsm
git commit -m "feat: estimate and group conversation context"
```

---

### Task 6: 实现 Size-Tiered Planner 和动态 Frontier

**Files:**
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmSummarySegment.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmContextManifest.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmCompactionPlan.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmPlanningInput.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/FrontierSelection.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmCompactionPlanner.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/WarmSegmentFrontierSelector.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmInvariantValidator.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmCompactionPlannerTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/WarmSegmentFrontierSelectorTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/planning/LsmPlannerStateMachineTest.java`

**Interfaces:**
- Consumes: Task 5 turns, policy and estimates plus Task 4 persisted manifest/segments.
- Produces: deterministic `LsmCompactionPlan` and non-overlapping `FrontierSelection` consumed by summary/runtime tasks.

- [ ] **Step 1: Write planner invariants before implementation**

Tests must explicitly cover fanout 3/5/10, maxLevel 3/4/5, no two-node merge, one ROOT, forced partial L0, Warm 100～109 and configuration reconciliation:

```java
@Test
void fiveAdjacentL0SegmentsPromoteOnceWhenFanoutIsFive() {
    LsmCompactionPlan plan = planner.plan(stateWithL0Segments(5), policy(5, 4), pressure());

    assertThat(plan.merges()).singleElement()
            .satisfies(merge -> {
                assertThat(merge.sourceIds()).hasSize(5);
                assertThat(merge.targetLevel()).isEqualTo(1);
            });
}

@Test
void frontierNeverContainsParentAndChild() {
    FrontierSelection selection = selector.select(treeWithParentAndChildren(), 8_000);
    assertThat(selection.segmentIds()).doesNotContain("parent", "child-1");
    validator.assertContinuous(selection.coverage());
}
```

- [ ] **Step 2: Run planner tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=LsmCompactionPlannerTest,WarmSegmentFrontierSelectorTest,LsmPlannerStateMachineTest test
```

Expected: FAIL because planner/domain types do not exist.

- [ ] **Step 3: Implement the deterministic state machine**

Planner order must be encoded exactly:

```java
public LsmCompactionPlan plan(LsmPlanningInput input) {
    HotBoundary hot = boundaries.protectCurrentAndLastCompleted(input.turns(), 10);
    List<PlannedOperation> operations = new ArrayList<>();
    operations.addAll(sealFullL0Batches(input, hot, 10));
    operations.addAll(promoteFullLevels(input, input.policy().fanout(), input.policy().maxLevel()));
    operations.addAll(trimWarmToRange(input, 100, 109));
    operations.addAll(planMajorCompactionWhenUseful(input, 3, 0.20d));
    return validateAndBuild(input, operations);
}
```

Define `HotBoundary` and `PlannedOperation` as nested immutable records in `LsmCompactionPlan`; define `FrontierSelection` as the ordered segment IDs, coverage and estimated token count returned by the selector.

Normal promotion uses exactly `fanout` adjacent same-level nodes. The configured top level is a singleton rolling ROOT. Major compaction may absorb ROOT plus at least three fully cold nodes only when projected Cold token reduction is at least 20%.

- [ ] **Step 4: Add a fixed-seed 10,000-turn state-machine test and run module tests**

For every successful simulated publish assert ordinary level counts `<= fanout-1`, ROOT count `<=1`, ordered complete coverage, Hot protection and Warm range. Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am spotless:apply test
```

Expected: PASS for all policy combinations.

- [ ] **Step 5: Commit planner and frontier selection**

```powershell
git add backend/agent-context-lsm
git commit -m "feat: plan LSM context compaction"
```

---

### Task 7: 实现摘要 Schema、生成器和 Ollama 适配器

**Files:**
- Create: `backend/agent-context-lsm/src/main/resources/schema/context-summary-v1.schema.json`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryPrompt.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryDocumentValidator.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/LsmSegmentKeyFactory.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/OllamaContextSummaryModelAdapter.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/OllamaModelProperties.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGeneratorTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model/OllamaContextSummaryModelAdapterTest.java`

**Interfaces:**
- Consumes: Task 1 summary port, Task 5 estimator and Task 6 planned node sources.
- Produces: validated immutable summary candidates with deterministic segment keys and no-tools Ollama summary calls.

- [ ] **Step 1: Write schema/generator/adapter tests**

The generator test must prove one retry, exact coverage validation, output caps, structured thinking carryover and deterministic key reuse. The HTTP stub adapter test must assert request JSON contains `num_ctx:65536`, no `tools`, `stream:false`, the configured model, and the layer-specific output limit.

```java
@Test
void retriesOneInvalidDocumentThenReturnsValidatedSummary() {
    when(model.summarize(any()))
            .thenReturn(result("{}"), result(validSummaryJson()));

    GeneratedSummary summary = generator.generate(requestForLevel(1));

    assertThat(summary.document().schemaVersion()).isEqualTo(1);
    verify(model, times(2)).summarize(any());
}
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-web -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=ContextSummaryGeneratorTest,OllamaContextSummaryModelAdapterTest test
```

Expected: FAIL because generator and adapter are absent.

- [ ] **Step 3: Implement the fixed summary schema and prompt**

Schema requires `schemaVersion`, coverage, and arrays named `confirmedFacts`, `userPreferences`, `workingHypotheses`, `decisions`, `rejectedApproaches`, `toolFindings`, `openQuestions`, `nextSteps`. Each entry requires a statement and source ranges. Prompt version 1 must say that source content is data, may not override system/tool constraints, and raw thinking must be converted to reusable state rather than copied.

Apply exact output caps:

```java
static int maxSummaryTokens(SegmentKind kind, int level) {
    if (kind == SegmentKind.ROOT) return 4_096;
    return Math.min(1_536 + 512 * level, 3_584);
}
```

Define `GeneratedSummary` as an immutable nested record of `ContextSummaryGenerator` containing the validated document, serialized JSON, estimated token count, provider, model and deterministic segment key. Define `SegmentKind` inside `LsmSummarySegment` with only `STANDARD` and `ROOT`.

- [ ] **Step 4: Implement the Ollama non-streaming adapter and run tests**

Add `contextWindowTokens=65536` and `safetyReserveTokens=4096` to `OllamaModelProperties`. The normal gateway and summary adapter must both send `num_ctx`; summary uses no tools and no user-visible delta callback. Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-web -am spotless:apply test
```

Expected: PASS, including existing normal Ollama streaming tests.

- [ ] **Step 5: Commit summary generation**

```powershell
git add backend/agent-context-lsm backend/agent-web
git commit -m "feat: generate structured context summaries"
```

---

### Task 8: 实现可恢复 Compaction Job 和原子 Manifest 发布

**Files:**
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionCoordinator.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionPlanningTransaction.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmManifestPublicationTransaction.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionRecoveryService.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionException.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionCoordinatorTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/LsmManifestPublicationIntegrationTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionRecoveryTest.java`

**Interfaces:**
- Consumes: Task 4 repositories, Task 6 plans, Task 7 generator and Task 1 execution control port.
- Produces: `compactIfRequired` with three transaction phases, reusable candidate segments and crash-safe publication.

- [ ] **Step 1: Write failure, CAS and takeover tests**

Cover crash after PLANNED, after one candidate, at READY, before publish and after publish. Assert old manifest remains active on every failure and stale owner cannot publish:

```java
@Test
void staleOwnerCannotPublishReadyCandidates() {
    ReadyCompaction ready = fixture.readyAgainstManifest(7, 3);
    fixture.replaceManifestWithGeneration(8);

    assertThat(publication.publish(ready)).isEqualTo(PublishResult.SUPERSEDED);
    assertThat(fixture.currentManifest().generation()).isEqualTo(8);
}
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=LsmCompactionCoordinatorTest,LsmManifestPublicationIntegrationTest,LsmCompactionRecoveryTest test
```

Expected: FAIL because runtime coordination classes are missing.

- [ ] **Step 3: Implement the three explicit phases**

Use this coordinator flow without a surrounding transaction:

```java
public CompactionOutcome compactIfRequired(CompactionRequest request) {
    PlannedCompaction planned = planning.begin(request);       // short transaction
    if (planned.noop()) return CompactionOutcome.unchanged();
    ReadyCompaction ready = generateCandidates(planned);       // no transaction
    return publication.publish(ready);                         // short transaction + CAS
}
```

Define `CompactionRequest`, `PlannedCompaction`, `ReadyCompaction`, `CompactionOutcome` and `PublishResult` as public nested records/enums of `LsmCompactionCoordinator`, so later tasks use one stable runtime surface rather than additional cross-package DTO files.

Planning persists policy snapshot, source manifest generation/version, input boundary and a body-free plan. Candidate generation reuses exact segment keys. Publication rechecks execution ownership, cancellation, source manifest, coverage and level counts before CAS.

- [ ] **Step 4: Implement recovery and bounded replanning**

Recovery finds a job by execution/attempt/input boundary, reuses READY candidates, and resumes from the first missing segment. CAS conflict marks the job SUPERSEDED and permits at most two replans. Cancellation marks CANCELLED. Two summary failures mark FAILED and raise stable `CONTEXT_COMPACTION_FAILED` without changing the old manifest.

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am spotless:apply test
```

Expected: PASS.

- [ ] **Step 5: Commit transactional compaction**

```powershell
git add backend/agent-context-lsm
git commit -m "feat: publish recoverable context compactions"
```

---

### Task 9: 完成 LSM 策略、模型上下文组装和 ReAct 接入

**Files:**
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/strategy/LsmConversationContextStrategy.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssembler.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextAutoConfiguration.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/WebContextExecutionControlAdapter.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInputMessage.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/OllamaAgentModelGateway.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/CodexAgentModelGateway.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ExecutionEventType.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ExecutionFailureCode.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactExecutionEngineIntegrationTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/strategy/LsmConversationContextStrategyTest.java`

**Interfaces:**
- Consumes: all prior plugin services plus Task 2 resolver and existing ReAct/model gateway.
- Produces: end-to-end SPI-selected context input, stable raw fallback, compaction events and failure convergence.

- [ ] **Step 1: Write strategy and ReAct integration tests**

Update the ReAct test to inject `ConversationContextStrategyResolver`, verify every model invocation fixes the persisted input boundary, and assert the selected strategy receives tools plus model budget. Add LSM strategy tests for no-op below 75%, compaction above 75%, hard-limit Hot degradation and current-turn failure.

```java
@Test
void invokesSelectedContextStrategyBeforeEveryModelCall() {
    when(contexts.prepare(any())).thenReturn(preparedUserQuestion());

    engine.execute(run);

    verify(contexts, times(3)).prepare(argThat(request ->
            request.execution().inputUpToMessageSequence() > 0));
}
```

- [ ] **Step 2: Run focused integration tests and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-server -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=LsmConversationContextStrategyTest,ReactExecutionEngineIntegrationTest test
```

Expected: FAIL because ReAct still depends directly on `ConversationContextLoader`.

- [ ] **Step 3: Implement the LSM strategy and assembler**

The strategy flow is:

```java
public PreparedConversationContext prepare(ConversationContextRequest request) {
    ContextPolicySnapshot frozen = policies.findOrCreateExecutionPolicy(
            request.execution(), request.policy());
    ContextState state = assembler.loadAndEstimate(request.withPolicy(frozen));
    if (state.estimatedTokens() <= request.budget().softTriggerTokens()) {
        return assembler.prepareCurrentView(state);
    }
    CompactionOutcome outcome = coordinator.compactIfRequired(state.toCompactionRequest());
    return assembler.preparePublishedView(outcome.manifest(), request);
}
```

`ContextState` is a package-private nested record of `LsmConversationContextAssembler`. The first line must persist or load the execution policy before checking 75%, ensuring later model invocations, retry and takeover use the original snapshot.

Assemble fixed system messages, a fixed untrusted-memory wrapper, ROOT, Cold Tail, Warm frontier, Hot raw turns and current turn in order. Persisted summary provenance stays server-side. If compactable history is exhausted and estimated input exceeds hard limit, degrade from the oldest Hot complete turn; never compress the current unfinished turn.

- [ ] **Step 4: Replace direct loader use and preserve provider mappings**

`ReactExecutionEngine` maps `PreparedConversationContext.messages()` to `ModelInputMessage`. Add optional historical thinking to `ModelInputMessage`; Ollama uses provider-native thinking when supported, while Codex uses the fixed historical-reasoning content projection. Add event values:

```java
CONTEXT_COMPACTION_STARTED("context_compaction_started"),
CONTEXT_COMPACTION_COMPLETED("context_compaction_completed"),
CONTEXT_COMPACTION_FAILED("context_compaction_failed")
```

Add failure codes `CONTEXT_COMPACTION_FAILED` and `CONTEXT_WINDOW_EXCEEDED`. Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-web,agent-server -am spotless:apply test
```

Expected: PASS for raw and LSM focused tests.

- [ ] **Step 5: Commit runtime integration**

```powershell
git add backend/agent-context-lsm backend/agent-web backend/agent-server/src/test
git commit -m "feat: integrate LSM context strategy"
```

---

### Task 10: 配置运行时插件、指标和安全边界

**Files:**
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/server/ModuleBoundaryTest.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/observability/LsmContextMetrics.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/ContextPluginActivationIntegrationTest.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/ContextCompactionSecurityIntegrationTest.java`
- Test: `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/observability/LsmContextMetricsTest.java`

**Interfaces:**
- Consumes: completed raw/LSM strategies and runtime beans.
- Produces: deployable configuration, low-cardinality metrics and executable plugin-removal/security gates.

- [ ] **Step 1: Write activation, boundary and metric tests**

Test these application contexts:

```text
strategy missing                → raw starts
strategy=raw, plugin on classpath → no LSM Flyway/strategy/table access
strategy=lsm, plugin on classpath → migration then LSM strategy starts
strategy=unknown               → startup failure
```

Security test injects marker secrets into message, thinking and tool payload, triggers compaction failure, captures logs/events/metrics and asserts no marker appears.

- [ ] **Step 2: Run focused server tests and verify missing configuration/metrics**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=ContextPluginActivationIntegrationTest,ContextCompactionSecurityIntegrationTest,LsmContextMetricsTest,ModuleBoundaryTest test
```

Expected: FAIL until configuration and metrics are wired.

- [ ] **Step 3: Add service configuration and metrics**

Add:

```yaml
data-agent:
  context:
    strategy: ${DATA_AGENT_CONTEXT_STRATEGY:raw}
    compaction:
      fanout: ${DATA_AGENT_CONTEXT_COMPACTION_FANOUT:5}
      max-level: ${DATA_AGENT_CONTEXT_COMPACTION_MAX_LEVEL:4}
```

Register the approved counters/timers/gauges without tenant/session/execution labels. Metrics may tag only result, type and level. Ensure log messages contain IDs, ranges, estimates, durations and stable error codes only.

- [ ] **Step 4: Run server and plugin tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm,agent-server -am spotless:apply test
```

Expected: PASS.

- [ ] **Step 5: Commit configuration and observability**

```powershell
git add backend/agent-context-lsm backend/agent-server
git commit -m "feat: configure and observe context compaction"
```

---

### Task 11: 完成端到端恢复验证和文档更新

**Files:**
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionRecoveryIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/LsmContextCompactionMultiTenantIntegrationTest.java`
- Modify: `README.md`
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Verify: `contracts/openapi.yaml`
- Verify: `contracts/schemas/session-sse-event.schema.json`

**Interfaces:**
- Consumes: completed feature.
- Produces: cross-module acceptance evidence, deployment documentation and a clean final worktree.

- [ ] **Step 1: Write end-to-end scenarios with a deterministic fake summary model**

Cover:

```text
second execution reads first execution history
75%+1 triggers compaction and reaches <=65%
Warm publishes in [100,109], Hot keeps 10 raw turns
fanout=3/maxLevel=3 and fanout=5/maxLevel=5
ROOT remains singleton while its coverage advances
lease takeover reuses candidates and fences old owner
Redis unavailable does not prevent PostgreSQL publication/replay
tenant A cannot read tenant B segments or manifest
raw strategy runs without touching plugin tables
```

- [ ] **Step 2: Run the new integration tests and fix only feature defects**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest,LsmContextCompactionMultiTenantIntegrationTest test
```

Expected: PASS. Do not weaken assertions or remove existing tests to obtain green output.

- [ ] **Step 3: Update supported configuration documentation**

Document `DATA_AGENT_CONTEXT_STRATEGY`, `DATA_AGENT_CONTEXT_COMPACTION_FANOUT` and `DATA_AGENT_CONTEXT_COMPACTION_MAX_LEVEL`, raw rollback procedure, plugin Flyway behavior and the supported verification command in `README.md`. Confirm OpenAPI and SSE schema already contain the three compaction event values; change them only if implementation payload violates the existing schema.

- [ ] **Step 4: Run full verification and inspect every affected repository status**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain,agent-context-lsm,agent-web,agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
git status --short
git diff --check HEAD~10..HEAD
```

Expected: both Maven commands exit 0; only intentional feature commits are present; no credentials, generated files or unrelated user changes are included.

- [ ] **Step 5: Commit final integration coverage and documentation**

```powershell
git add backend/agent-server/src/test README.md contracts
git commit -m "test: verify LSM context compaction"
```

---

## Final Review Checklist

- [ ] Every approved design requirement maps to at least one task and automated test.
- [ ] `agent-persistence` contains no new LSM classes, Mapper, Entity, Service or migration.
- [ ] `agent-context-lsm` has no compile dependency on `agent-web` or `agent-server`.
- [ ] `raw` starts when the plugin is absent or disabled.
- [ ] LSM plugin Flyway uses its own location/history and runs after the primary Flyway.
- [ ] Fixed system/tool input is counted but never summarized.
- [ ] Message/thinking source remains immutable and summary output is structured.
- [ ] 75% trigger, 65% target, hard-limit fallback, Warm 100～109 and Hot 10 are tested.
- [ ] fanout/maxLevel configuration changes are deterministic across retry/takeover.
- [ ] ROOT singleton, level caps, coverage continuity and parent/child exclusion are tested.
- [ ] Candidate reuse, CAS conflicts, cancellation, crash recovery and old-owner fencing are tested.
- [ ] Logs, events, errors and metrics contain no sensitive body content.
- [ ] Full backend `mvn verify` passes from a clean status inspection.
