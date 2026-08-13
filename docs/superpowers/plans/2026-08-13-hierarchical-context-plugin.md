# Hierarchical Context Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有 LSM 行为的前提下中立化上下文策略 SPI，并新增可独立安装、持久化和切换的 `agent-context-hierarchical` Hot/Warm/Cold 二进制摘要插件。

**Architecture:** `agent-domain` 保留供应商无关的上下文策略、消息、模型预算、摘要模型和 execution control 契约；`agent-web` 继续负责 raw 策略、请求工厂、策略解析和运行时端口适配。现有 `agent-context-lsm` 把自己的 policy 和 75%/65% 水位收回插件内部；新的 `agent-context-hierarchical` 通过同一 `ConversationContextStrategy` 注册 `hierarchical`，独立承载二进制摘要树、Cold checkpoint、compaction job、MyBatis 持久化、Flyway、恢复和指标。

**Tech Stack:** Java 21、Spring Boot 4.1、Maven、MyBatis-Plus 3.5.16、PostgreSQL、Flyway、Jackson、Micrometer、JUnit 5、AssertJ、Mockito、Testcontainers。

## Global Constraints

- 在当前 `data-agent-v2` 工作区直接开发，不创建、切换或使用额外 Git worktree。
- 每个任务遵循 TDD：先写失败测试，确认失败原因，再实现最小代码并运行相关测试。
- 每个任务完成并验证后创建一个独立 commit，不把多个任务压成一个提交。
- 不修改 `agent-persistence` 的 Entity、Mapper、ServiceImpl、migration 或增加 hierarchical 专用查询。
- `agent-context-hierarchical` 不依赖 `agent-web`、`agent-server` 或 `agent-context-lsm`。
- `agent-context-lsm` 与 `agent-context-hierarchical` 不共享私有表、Mapper、Repository、策略快照或 Flyway history。
- `ConversationContextStrategy`、`ContextSummaryModelPort` 和 `ContextExecutionControlPort` 是唯一宿主接入契约，不增加第二套 Router、Snapshot 或模型端口。
- 现有 LSM 必须保持 75% 触发、65% 目标、execution policy 冻结、manifest publication、恢复和指标行为。
- hierarchical 使用 75% 触发、70% 目标、最近 10 个完整问答 Hot 原文、最近 100 轮 Hot+Warm 和 100 轮之前 Cold 滚动摘要。
- system prompt、工具定义和协议开销参与 token 估算，但不得被摘要或替换。
- 当前未完成问答永不压缩；只有超过硬输入上限时才从 Hot 最老完整问答开始降级。
- 新增字段必须写字段注释；增量代码按逻辑块添加用途和关键逻辑注释。
- 新增字符串常量集中为 `static final`；配置 key、默认值和 error message 可直接声明。
- 不引入新第三方依赖；使用现有 Jackson、MyBatis-Plus、Flyway、Micrometer 和测试依赖。
- 不删除、弱化或改写现有测试；LSM 回归测试是共享 SPI 重构的发布门槛。
- 原始 message、thinking、tool input/output 不因压缩被修改或删除，也不得进入日志、指标或事件正文。

---

## File Structure

### Existing modules changed

- `backend/agent-domain/.../conversation/context/*`：中立 SPI、物理预算和结果 generation。
- `backend/agent-web/.../conversation/context/*`：去除请求工厂中的 LSM policy 构造，保留现有 resolver 和端口适配。
- `backend/agent-context-lsm/...`：私有化 LSM policy，并把 75%/65% 预算计算移回 LSM。
- `backend/agent-server/pom.xml`、`README.md`：安装和说明新策略。

### New plugin module

```text
backend/agent-context-hierarchical/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/datagent/agentv2/context/hierarchical/
    │   │   ├── configuration/   # 条件自动配置、属性、Mapper 和 Flyway
    │   │   ├── domain/          # policy、turn、node、checkpoint、job、plan、frontier
    │   │   ├── persistence/     # 私有 Entity、Mapper、Repository 和 JSON codec
    │   │   ├── planning/        # 分组、估算、二叉规划和 frontier 选择
    │   │   ├── summary/         # prompt、schema 校验、生成 identity 和摘要生成
    │   │   ├── runtime/         # 上下文组装、事务、协调、恢复和发布
    │   │   ├── strategy/        # 唯一 SPI 实现
    │   │   └── observability/   # Micrometer 指标
    │   └── resources/
    │       ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       ├── db/agent-context-hierarchical/migration/V1__create_hierarchical_context_store.sql
    │       └── schema/context-summary-v1.schema.json
    └── test/java/com/datagent/agentv2/context/hierarchical/
```

---

### Task 1: 中立化共享 SPI 并保持 LSM 行为

**Files:**
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextBudget.java`
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextRequest.java`
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/PreparedConversationContext.java`
- Delete: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextPolicySnapshot.java`
- Modify: `backend/agent-domain/src/test/java/com/datagent/agentv2/conversation/context/ContextContractsTest.java`
- Create: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmContextPolicySnapshot.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextProperties.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/strategy/LsmConversationContextStrategy.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/planning/LsmContextPolicy.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmExecutionPolicyRepository.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/json/LsmDocumentCodec.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/SummaryGenerationIdentity.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/domain/LsmPlanningInput.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionCoordinator.java`
- Modify: `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssembler.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextRequestFactory.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/RawConversationContextStrategy.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/WebContextExecutionControlAdapter.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/context/ConversationContextStrategyResolverTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactExecutionEngineIntegrationTest.java`
- Modify: all existing `agent-context-lsm` tests importing `ContextPolicySnapshot` or reading `manifestGeneration` as identified by `rg -n "ContextPolicySnapshot|manifestGeneration" backend/agent-context-lsm backend/agent-server`.

**Interfaces:**
- Consumes: existing `ConversationContextStrategy#prepare(ConversationContextRequest)` and LSM persisted `policy_snapshot` JSON.
- Produces: policy-neutral `ConversationContextRequest`, physical `ContextBudget`, `PreparedConversationContext#strategyGeneration()`, and private `LsmContextPolicySnapshot` without changing LSM database documents.

- [ ] **Step 1: Rewrite contract tests to express the neutral API**

```java
@Test
void contextBudgetOnlyOwnsPhysicalLimits() {
    ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 128);

    assertThat(budget.hardInputLimitTokens()).isEqualTo(57_344);
}

@Test
void requestContainsNoStrategyPolicy() {
    ConversationContextRequest request =
            new ConversationContextRequest(execution(), budget(), List.of(), List.of());

    assertThat(request.execution().executionId()).isEqualTo("execution-1");
}

@Test
void preparedContextUsesStrategyNeutralGeneration() {
    PreparedConversationContext prepared =
            new PreparedConversationContext("raw", List.of(), 12, false, 0L);

    assertThat(prepared.strategyGeneration()).isZero();
}
```

- [ ] **Step 2: Run the contract test and confirm it fails against the current API**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain -am `
  '-Dtest=ContextContractsTest' test
```

Expected: compilation fails because `ConversationContextRequest` still requires `ContextPolicySnapshot` and `PreparedConversationContext` still exposes `manifestGeneration`.

- [ ] **Step 3: Implement the neutral domain contracts**

```java
public record ConversationContextRequest(
        ContextExecutionRef execution,
        ContextBudget budget,
        List<ContextMessage> fixedSystemMessages,
        List<ContextToolDefinition> toolDefinitions) {}

public record ContextBudget(
        int contextWindowTokens,
        int maxOutputTokens,
        int safetyReserveTokens,
        int fixedInputTokens) {

    public int hardInputLimitTokens() {
        return contextWindowTokens - maxOutputTokens - safetyReserveTokens;
    }
}

public record PreparedConversationContext(
        String strategyId,
        List<ContextMessage> messages,
        int estimatedInputTokens,
        boolean compacted,
        long strategyGeneration) {}
```

Preserve the existing constructor null checks, immutable copies and non-negative counter validation. Remove `ContextPolicySnapshot` only after all production references have migrated.

- [ ] **Step 4: Add LSM policy regression tests before migrating production code**

```java
@Test
void lsmPolicyKeepsExistingWatermarks() {
    LsmContextPolicySnapshot policy = LsmContextPolicySnapshot.defaults(5, 4);
    ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);

    assertThat(policy.softTriggerTokens(budget)).isEqualTo(49_152);
    assertThat(policy.targetInputTokens(budget)).isEqualTo(42_598);
}
```

Also retain the existing tests proving `fanout` is 3–10, `maxLevel` is 1–8, and a persisted policy wins over changed service configuration.

- [ ] **Step 5: Move LSM policy ownership into `agent-context-lsm`**

```java
public record LsmContextPolicySnapshot(
        int policyVersion,
        int fanout,
        int maxLevel,
        int baseSegmentTurns,
        int compressionThresholdPercent,
        int targetWatermarkPercent,
        int warmTargetTurns,
        int warmMaxTurns,
        int protectedHotTurns) {

    public int softTriggerTokens(ContextBudget budget) {
        return Math.floorDiv(
                budget.contextWindowTokens() * compressionThresholdPercent, 100);
    }

    public int targetInputTokens(ContextBudget budget) {
        return Math.floorDiv(
                budget.contextWindowTokens() * targetWatermarkPercent, 100);
    }
}
```

`LsmContextProperties#snapshot()` must construct the same JSON field values currently produced by `ConversationContextRequestFactory`: policy version 1, base segment 10, threshold 75, target 65, warm target 100, warm max 109 and protected Hot 10. Keep Jackson property names unchanged so existing `policy_snapshot` rows deserialize without migration.

- [ ] **Step 6: Update LSM strategy freezing and all LSM policy consumers**

```java
LsmContextPolicySnapshot frozen =
        policies.findOrCreateExecutionPolicy(
                request.execution(), properties.snapshot());
LsmConversationContextAssembler.ContextState state =
        assembler.loadAndEstimate(request, frozen);
```

Change planner, generator, codec, repositories and runtime request records to accept `LsmContextPolicySnapshot`. Replace calls to `request.policy()` with an explicit private LSM policy parameter. Do not change migration SQL or stored JSON field names.

- [ ] **Step 7: Remove LSM policy construction from the Web request factory**

```java
return new ConversationContextRequest(
        executionRef,
        budgets.currentBudget(),
        fixedSystemMessages,
        contextTools);
```

Remove `fanout`, `maxLevel` and all LSM constants from `ConversationContextRequestFactory`. Update raw strategy and integration fixtures to return/read `strategyGeneration`.

- [ ] **Step 8: Run focused shared and LSM regression tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B `
  -pl agent-domain,agent-context-lsm,agent-web,agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ContextContractsTest,ConversationContextStrategyResolverTest,LsmContextPolicyTest,LsmConversationContextStrategyTest,LsmRepositoryIntegrationTest,LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest,ReactExecutionEngineIntegrationTest' test
```

Expected: PASS; existing persisted policy JSON loads, LSM still triggers at 49,152 tokens, targets 42,598 tokens and publishes the same generation values through `strategyGeneration`.

- [ ] **Step 9: Commit the behavior-preserving SPI refactor**

```powershell
git add backend/agent-domain backend/agent-context-lsm backend/agent-web backend/agent-server/src/test
git commit -m "refactor: neutralize conversation context SPI"
```

---

### Task 2: 创建独立 hierarchical 插件模块和条件装配

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-context-hierarchical/pom.xml`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalContextPolicySnapshot.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextProperties.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfiguration.java`
- Create: `backend/agent-context-hierarchical/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfigurationTest.java`

**Interfaces:**
- Consumes: neutral Task 1 SPI and Spring Boot AutoConfiguration.
- Produces: a reactor module that remains inert unless `data-agent.context.strategy=hierarchical`.

- [ ] **Step 1: Add an auto-configuration isolation test**

```java
@Test
void rawDoesNotBindHierarchicalPropertiesOrCreatePluginBeans() {
    contextRunner
            .withPropertyValues("data-agent.context.strategy=raw")
            .run(context ->
                    assertThat(context)
                            .doesNotHaveBean(HierarchicalContextProperties.class));
}

@Test
void hierarchicalRejectsTargetAtOrAboveTrigger() {
    contextRunner
            .withPropertyValues(
                    "data-agent.context.strategy=hierarchical",
                    "data-agent.context.hierarchical.trigger-ratio=0.75",
                    "data-agent.context.hierarchical.target-ratio=0.75")
            .run(context -> assertThat(context).hasFailed());
}
```

- [ ] **Step 2: Run the new module test and confirm the module is absent**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

Expected: Maven fails because the reactor module does not exist.

- [ ] **Step 3: Create the module POM and add it to the reactor**

Declare dependencies on `agent-domain`, `agent-persistence`, `spring-boot-autoconfigure`, validation, JDBC, Flyway, PostgreSQL runtime, MyBatis-Plus, Jackson, Micrometer, JSON schema validator, Spring Boot test and Testcontainers PostgreSQL. Do not declare `agent-web`, `agent-server` or `agent-context-lsm`.

- [ ] **Step 4: Implement validated plugin properties**

```java
public record HierarchicalContextPolicySnapshot(
        int policyVersion,
        double triggerRatio,
        double targetRatio,
        int recentRawTurns,
        int warmWindowTurns,
        int summarySchemaVersion,
        int summaryPromptVersion) {

    public int softTriggerTokens(ContextBudget budget) {
        return (int) Math.floor(
                budget.contextWindowTokens() * triggerRatio);
    }

    public int targetInputTokens(ContextBudget budget) {
        return (int) Math.floor(
                budget.contextWindowTokens() * targetRatio);
    }
}

@Validated
@ConfigurationProperties("data-agent.context.hierarchical")
public class HierarchicalContextProperties {
    private double triggerRatio = 0.75d;
    private double targetRatio = 0.70d;
    private int recentRawTurns = 10;
    private int warmWindowTurns = 100;

    public HierarchicalContextPolicySnapshot snapshot() {
        return new HierarchicalContextPolicySnapshot(
                1, triggerRatio, targetRatio, recentRawTurns,
                warmWindowTurns, 1, 1);
    }
}
```

Use a class-level validation method to enforce `0 < targetRatio < triggerRatio < 1` and `warmWindowTurns > recentRawTurns`. Do not expose binary fanout or max level; fanout is 2 and the level is derived from the Warm range.

- [ ] **Step 5: Register a conditional empty auto-configuration shell**

```java
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@EnableConfigurationProperties(HierarchicalContextProperties.class)
public class HierarchicalContextAutoConfiguration {}
```

Register the class in `AutoConfiguration.imports`. At this task the configuration intentionally exposes only validated properties; strategy and persistence beans arrive in later tasks.

- [ ] **Step 6: Run the module auto-configuration test**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalContextAutoConfigurationTest' test
```

Expected: PASS; raw is inert and invalid hierarchical ratios fail binding.

- [ ] **Step 7: Commit the plugin module boundary**

```powershell
git add backend/pom.xml backend/agent-context-hierarchical
git commit -m "feat: add hierarchical context plugin module"
```

---

### Task 3: 实现插件私有策略、数据库迁移和 Repository

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCheckpoint.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalSummaryNode.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCompactionJob.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalExecutionPolicyEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalCheckpointEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalSummaryNodeEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/entity/HierarchicalCompactionJobEntity.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalExecutionPolicyMapper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalCheckpointMapper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalSummaryNodeMapper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalCompactionJobMapper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalExecutionPolicyRepository.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalCheckpointRepository.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalSummaryNodeRepository.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalCompactionJobRepository.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/json/HierarchicalDocumentCodec.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalMapperConfiguration.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalFlywayMigrator.java`
- Create: `backend/agent-context-hierarchical/src/main/resources/db/agent-context-hierarchical/migration/V1__create_hierarchical_context_store.sql`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfiguration.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalFlywayIntegrationTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/persistence/HierarchicalRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `ContextExecutionRef`, `HierarchicalContextProperties#snapshot()`, shared DataSource and MyBatis interceptors.
- Produces: private policy freezing, immutable nodes, CAS checkpoint publication and recoverable compaction jobs.

- [ ] **Step 1: Write migration tests for plugin isolation and constraints**

Assert that migration location `classpath:db/agent-context-hierarchical/migration` creates exactly these tables:

```text
conversation_context_hierarchical_execution_policy
conversation_context_hierarchical_checkpoint
conversation_context_hierarchical_summary_node
conversation_context_hierarchical_compaction_job
```

Assert a separate history table named `flyway_agent_context_hierarchical_schema_history`, tenant/session composite foreign keys, unique execution policy, unique active checkpoint, immutable node identity, non-negative sequence/token/generation checks and legal job statuses.

- [ ] **Step 2: Run migration tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalFlywayIntegrationTest' test
```

Expected: FAIL because migration and tables do not exist.

- [ ] **Step 3: Implement the private migration and Flyway wrapper**

Use the existing LSM pattern but distinct constants:

```java
static final String MIGRATION_LOCATION =
        "classpath:db/agent-context-hierarchical/migration";
static final String HISTORY_TABLE =
        "flyway_agent_context_hierarchical_schema_history";
```

Wrap the plugin Flyway instead of exposing a `Flyway` bean, depend on `flywayInitializer`, enable baseline version 0, and run only when strategy is `hierarchical`.

- [ ] **Step 4: Write repository tests for policy freezing, node idempotency and checkpoint CAS**

```java
@Test
void executionPolicyIsFrozenOnFirstUse() {
    HierarchicalContextPolicySnapshot first = policy(0.75d, 0.70d);
    HierarchicalContextPolicySnapshot changed = policy(0.80d, 0.60d);

    repository.findOrCreate(execution(), first);

    assertThat(repository.findOrCreate(execution(), changed)).isEqualTo(first);
}

@Test
void checkpointCasRejectsStaleVersionAndPointerRegression() {
    HierarchicalCheckpoint current = repository.create(checkpoint(20L, 1L));

    assertThat(repository.compareAndSet(current, checkpoint(10L, 2L))).isFalse();
    assertThat(repository.compareAndSet(current, checkpoint(30L, 2L))).isTrue();
}
```

Also test duplicate immutable nodes return the existing node and a job transition requires expected status/version/owner/attempt.

- [ ] **Step 5: Implement entities, codecs, mappers and repositories**

Expose repository operations with these stable signatures:

```java
HierarchicalContextPolicySnapshot findOrCreate(
        ContextExecutionRef execution,
        HierarchicalContextPolicySnapshot configured);

HierarchicalCheckpoint findCheckpoint(String conversationSessionId);

boolean compareAndSetCheckpoint(
        HierarchicalCheckpoint expected,
        HierarchicalCheckpoint replacement,
        ContextExecutionRef execution);

HierarchicalSummaryNode insertOrSelect(HierarchicalSummaryNode candidate);

boolean transitionJob(
        String jobId,
        HierarchicalCompactionJob.Status expected,
        HierarchicalCompactionJob.Status replacement,
        ContextExecutionRef execution,
        String failureCode);
```

Define `HierarchicalCompactionJob.Status` as the private enum `PLANNED`, `GENERATING`, `READY_TO_PUBLISH`, `SUCCEEDED`, `FAILED` and `CANCELLED`. The domain job owns status/version/owner/attempt fields; do not export these lifecycle details into `agent-domain`.

All repositories must call `TenantContext.requiredTenantId()` and rely on the existing tenant interceptor. JSON codecs must reject unknown schema versions and malformed documents.

- [ ] **Step 6: Register plugin Mapper scan and migration ordering**

```java
@MapperScan(
        basePackages =
                "com.datagent.agentv2.context.hierarchical.persistence.mapper",
        annotationClass = Mapper.class)
class HierarchicalMapperConfiguration {}
```

Import mapper and Flyway configuration from auto-configuration. Any strategy bean added later must use `@DependsOn("hierarchicalContextFlyway")`.

- [ ] **Step 7: Run persistence tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalFlywayIntegrationTest,HierarchicalRepositoryIntegrationTest' test
```

Expected: PASS for tenant isolation, constraints, policy freezing, node idempotency, job fencing and checkpoint CAS.

- [ ] **Step 8: Commit private persistence**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: persist hierarchical context state"
```

---

### Task 4: 重建完整问答并实现保守 token 估算

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/ConversationTurn.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalContextState.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoader.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouper.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTokenEstimator.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTurnGrouperTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/ConversationTokenEstimatorTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoaderTest.java`

**Interfaces:**
- Consumes: `ConversationContextRequest`, existing `AgentExecutionService`, `ConversationMessageService`, `ToolExecutionService` and persisted `thinkingProcess`.
- Produces: ordered atomic `ConversationTurn` values and complete-input token estimates including fixed framework.

- [ ] **Step 1: Write turn grouping tests**

Cover completed turns, current incomplete turn, retry sharing one triggering user message, assistant thinking, multiple model invocations, and indivisible tool call/observation pairs:

```java
@Test
void groupsRetryAndToolProtocolIntoOneCompletedTurn() {
    List<ConversationTurn> turns = grouper.group(messagesWithFailedRunAndRetry());

    assertThat(turns).hasSize(1);
    assertThat(turns.getFirst().completed()).isTrue();
    assertThat(turns.getFirst().messages())
            .extracting(ContextMessage::role)
            .containsExactly(USER, ASSISTANT, TOOL, ASSISTANT);
}
```

- [ ] **Step 2: Write estimator tests for every input component**

```java
@Test
void estimateIncludesFixedSystemToolsProtocolThinkingAndHistory() {
    int estimate = estimator.estimate(request(), contextMessages());

    assertThat(estimate)
            .isGreaterThan(request().budget().fixedInputTokens());
    assertThat(estimator.estimateTools(request().toolDefinitions())).isPositive();
}
```

Use fixed CJK, Latin, JSON, tool ID and thinking fixtures. Verify that adding any component strictly increases the estimate and that the estimator never uses safety reserve as business input.

- [ ] **Step 3: Run tests and confirm missing implementation failures**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=ConversationTurnGrouperTest,ConversationTokenEstimatorTest,HierarchicalConversationHistoryLoaderTest' test
```

Expected: compilation failure for missing classes.

- [ ] **Step 4: Implement deterministic history loading**

`HierarchicalConversationHistoryLoader#load(ContextExecutionRef)` must:

1. Read the session through `AgentExecutionService` and enforce the execution/session match.
2. Read messages only through `inputUpToMessageSequence`.
3. Index tool executions across all assistant execution IDs.
4. Reconstruct assistant thinking, tool calls and terminal tool results in provider-neutral `ContextMessage` order.
5. Reject mismatched tool execution IDs/call IDs/names/invocation sequences.
6. Use stored bounded previews when tool payloads were truncated.

- [ ] **Step 5: Implement turn grouping and token estimation**

```java
public record ConversationTurn(
        long ordinal,
        long startMessageSequence,
        long endMessageSequence,
        boolean completed,
        List<ContextMessage> messages) {}
```

The estimator must expose:

```java
int estimate(ConversationContextRequest request, List<ContextMessage> messages);
int estimateMessages(List<ContextMessage> messages);
int estimateTools(List<ContextToolDefinition> tools);
int estimateSummary(String summaryDocument);
```

Use conservative character-based rules already validated by LSM tests, but implement the class privately so the plugins do not couple.

- [ ] **Step 6: Run focused history tests**

Run the command from Step 3.

Expected: PASS with exact message ordering, retained thinking and stable estimates.

- [ ] **Step 7: Commit history reconstruction and estimation**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: reconstruct hierarchical context history"
```

---

### Task 5: 实现 Hot/Warm/Cold 二叉规划与 frontier 选择

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/SummaryRange.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/HierarchicalCompactionPlan.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/domain/FrontierSelection.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalContextPolicy.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/BinarySummaryTreePlanner.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/WarmFrontierSelector.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalInvariantValidator.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalContextPolicyTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/BinarySummaryTreePlannerTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/planning/WarmFrontierSelectorTest.java`

**Interfaces:**
- Consumes: frozen hierarchical policy, `ContextBudget`, turns, current checkpoint and available immutable nodes.
- Produces: deterministic Cold promotion ranges, missing binary nodes, published frontier and optional Hot degradation ranges.

- [ ] **Step 1: Write budget policy tests for 75%/70%/hard limit**

```java
@Test
void computesConfiguredWatermarksFromPhysicalBudget() {
    HierarchicalContextPolicy policy = new HierarchicalContextPolicy(snapshot());
    ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);

    assertThat(policy.softTriggerTokens(budget)).isEqualTo(49_152);
    assertThat(policy.targetInputTokens(budget)).isEqualTo(45_875);
    assertThat(policy.hardInputLimitTokens(budget)).isEqualTo(57_344);
}
```

- [ ] **Step 2: Write binary range and frontier tests**

Verify:

```text
level 0 = 1 turn
level 1 = 2 adjacent turns
level 2 = 4 adjacent turns
parent range = exact continuous union of left and right children
```

Test that one frontier covers Warm without gaps/overlap, never contains parent and child together, coarsens oldest history first and expands newest history first when budget increases.

- [ ] **Step 3: Write tier boundary tests**

For 157 completed turns assert:

```text
Cold target = 1..57
Warm target = 58..147
Hot target = 148..157
```

Also verify fewer than 10/100 turns, exactly 10/100 turns, incomplete current turn, and Cold pointer that already covers part of the target.

- [ ] **Step 4: Run planner tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalContextPolicyTest,BinarySummaryTreePlannerTest,WarmFrontierSelectorTest' test
```

Expected: compilation failure for missing planner classes.

- [ ] **Step 5: Implement deterministic binary planning**

```java
public record SummaryRange(
        long startTurn,
        long endTurn,
        long startMessageSequence,
        long endMessageSequence,
        int level) {

    public long coveredTurns() {
        return endTurn - startTurn + 1L;
    }
}
```

`BinarySummaryTreePlanner` must create a parent only from adjacent same-level children and derive maximum useful level from the Warm range. `WarmFrontierSelector` must return node IDs in chronological order and report missing ranges instead of calling the model.

- [ ] **Step 6: Implement invariant validation**

Reject non-power-of-two coverage, gaps, overlap, parent/child duplication, ranges before the Cold pointer, frontier outside Warm and any range containing the current incomplete turn.

- [ ] **Step 7: Run planner tests**

Run the command from Step 4.

Expected: PASS for all tier, binary and budget cases.

- [ ] **Step 8: Commit deterministic planning**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: plan hierarchical context compaction"
```

---

### Task 6: 生成并验证结构化摘要节点

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/resources/schema/context-summary-v1.schema.json`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryPrompt.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryDocumentValidator.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalNodeKeyFactory.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalSummaryGenerator.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/summary/SummaryGenerationIdentity.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/ContextSummaryDocumentValidatorTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/summary/HierarchicalSummaryGeneratorTest.java`

**Interfaces:**
- Consumes: `ContextSummaryModelPort`, source turns or ordered child summaries, frozen policy and token estimator.
- Produces: validated immutable summary candidates with deterministic identity and at most two model attempts.

- [ ] **Step 1: Write schema validation tests**

The schema must require arrays named:

```json
{
  "confirmedFacts": [],
  "workingHypotheses": [],
  "decisions": [],
  "rejectedApproaches": [],
  "toolFindings": [],
  "nextSteps": [],
  "openQuestions": []
}
```

Reject missing fields, wrong types, unknown schema version, oversized fields and summary token counts above the configured node limit.

- [ ] **Step 2: Write generator retry and identity tests**

```java
@Test
void retriesOneInvalidDocumentAndReturnsSecondValidDocument() {
    when(model.summarize(any()))
            .thenReturn(result("{\"confirmedFacts\":{}}"))
            .thenReturn(result(validSummary()));

    GeneratedSummary generated = generator.generate(request());

    assertThat(generated.document()).isEqualTo(validSummary());
    verify(model, times(2)).summarize(any());
}
```

Also prove the node key changes when range, schema version, prompt version or model identity changes, and remains stable for identical input.

- [ ] **Step 3: Run summary tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=ContextSummaryDocumentValidatorTest,HierarchicalSummaryGeneratorTest' test
```

Expected: compilation failure for missing summary implementation.

- [ ] **Step 4: Implement fixed prompt and source documents**

The prompt must distinguish facts, hypotheses, decisions and rejected approaches; preserve identifiers, dates, metric definitions, filters, units and tool failures; remove superseded conclusions; prohibit verbatim reasoning; and require schema-only JSON. Leaf source documents contain complete message/thinking/tool history for their turn range. Parent source documents contain only left summary then right summary. Cold source documents contain old Cold summary then promoted nodes in chronological order.

- [ ] **Step 5: Implement validation, deterministic keys and two-attempt generation**

```java
public GeneratedSummary generate(GenerationRequest request) {
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
        try {
            ContextSummaryResult result = model.summarize(toModelRequest(request));
            validator.validate(result.content(), request.maxSummaryTokens());
            return toGeneratedSummary(request, result);
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }
    throw new HierarchicalCompactionException(
            FAILURE_CONTEXT_COMPACTION, lastFailure);
}
```

Do not log the source document, prompt or result content.

- [ ] **Step 6: Run summary tests**

Run the command from Step 3.

Expected: PASS for schema, ordering, identity and retry behavior.

- [ ] **Step 7: Commit summary generation**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: generate hierarchical context summaries"
```

---

### Task 7: 组装当前和已发布的模型上下文

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalContextWindowExceededException.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssemblerTest.java`

**Interfaces:**
- Consumes: history loader, grouper, estimator, checkpoint/node repositories, policy and `ConversationContextRequest`.
- Produces: `HierarchicalContextState`, current-view `PreparedConversationContext`, published-view `PreparedConversationContext` and compaction input.

- [ ] **Step 1: Write no-compaction and published-view tests**

```java
@Test
void currentViewKeepsFixedFrameworkAndAllRawHistoryBelowTrigger() {
    HierarchicalContextState state = assembler.loadAndEstimate(request(), policy());

    PreparedConversationContext prepared = assembler.prepareCurrentView(state);

    assertThat(prepared.strategyId()).isEqualTo("hierarchical");
    assertThat(prepared.compacted()).isFalse();
    assertThat(prepared.messages()).containsExactlyElementsOf(allRawMessages());
}

@Test
void publishedViewUsesColdFrontierHotAndCurrentWithoutOverlap() {
    PreparedConversationContext prepared =
            assembler.preparePublishedView(checkpoint(), request(), policy());

    assertThat(prepared.strategyGeneration()).isEqualTo(8L);
    assertChronologicalAndNonOverlapping(prepared.messages());
}
```

- [ ] **Step 2: Add hard-limit and Hot protection tests**

Verify that a 70%–hard-limit protected minimum returns `targetReached=false` through internal state but keeps all 10 Hot turns raw; only a hard-limit breach replaces the oldest Hot turns with emergency summary nodes; fixed framework and current incomplete turn alone above hard limit throw `CONTEXT_WINDOW_EXCEEDED`.

- [ ] **Step 3: Run assembler tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalConversationContextAssemblerTest' test
```

Expected: compilation failure for missing assembler.

- [ ] **Step 4: Implement `HierarchicalContextState` loading and estimation**

```java
public record HierarchicalContextState(
        ConversationContextRequest request,
        HierarchicalContextPolicySnapshot policy,
        List<ConversationTurn> completedTurns,
        ConversationTurn currentTurn,
        HierarchicalCheckpoint checkpoint,
        int estimatedTokens) {}
```

The assembler must compute the complete estimate before any compaction decision and include fixed system messages, tools, protocol overhead, Cold, selected Warm, Hot and current turn.

- [ ] **Step 5: Implement prepared message ordering**

The final order is:

```text
fixed system messages
Cold summary system message, when present
published Warm summary messages in chronological range order
raw Hot user/assistant/tool protocol
current incomplete turn
```

Do not emit both a parent and descendant node. Do not read raw messages at or before the Cold pointer into the final model context.

- [ ] **Step 6: Run assembler tests**

Run the command from Step 3.

Expected: PASS for raw, published, target-not-reached, Hot degradation and hard failure paths.

- [ ] **Step 7: Commit context assembly**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: assemble hierarchical model context"
```

---

### Task 8: 持久化压缩计划并生成候选节点

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionException.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionPlanningTransaction.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCandidateGenerationService.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionPlanningTransactionTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCandidateGenerationServiceTest.java`

**Interfaces:**
- Consumes: assembler compaction state, binary planner, job/node repositories, summary generator and `ContextExecutionControlPort`.
- Produces: persisted `PLANNED` job and idempotently generated node candidates ending in `READY_TO_PUBLISH`.

Define `CompactionRequest` and `PlannedCompaction` as package-private records owned by `HierarchicalCompactionPlanningTransaction`. Define `ReadyCompaction` as a package-private record owned by `HierarchicalCandidateGenerationService`. These records carry IDs, ranges and generation metadata only; they must not duplicate message or summary bodies already owned by the assembler or immutable node store.

- [ ] **Step 1: Write transaction-boundary and event tests**

Verify planning calls `assertOwnedAndWithinBudget`, freezes the base checkpoint version/generation and input boundary, persists a body-free plan, and publishes exactly one `STARTED` event after the job insert. Verify cancellation marks the job failed/cancelled without invoking the summary model.

- [ ] **Step 2: Write candidate generation tests**

Test leaf generation from raw turns, parent generation from ordered children, Cold generation from old Cold plus promoted nodes, duplicate node reuse, one invalid/one valid summary retry, and no database transaction held while `ContextSummaryModelPort.summarize` executes.

- [ ] **Step 3: Run tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCompactionPlanningTransactionTest,HierarchicalCandidateGenerationServiceTest' test
```

Expected: compilation failure for missing runtime services.

- [ ] **Step 4: Implement planning as a short transaction**

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public PlannedCompaction plan(CompactionRequest request) {
    executions.assertOwnedAndWithinBudget(request.execution());
    HierarchicalCompactionPlan plan = planner.plan(request.planningInput());
    HierarchicalCompactionJob job = jobs.insertPlanned(request, plan);
    executions.publish(
            request.execution(),
            new ContextLifecycleEvent(STARTED, safeStartedMetadata(job)));
    return new PlannedCompaction(job, request, plan);
}
```

The persisted `plan_document` contains ranges, levels, node IDs, base checkpoint version/generation and target token budget, but no message or summary text.

- [ ] **Step 5: Implement generation outside the transaction**

Generate missing nodes bottom-up. Before every model call, assert ownership and cancellation. Insert each validated node idempotently. Transition the job from `PLANNED` to `GENERATING`, then to `READY_TO_PUBLISH` only when every planned candidate exists and passes invariants.

- [ ] **Step 6: Run planning and generation tests**

Run the command from Step 3.

Expected: PASS for transaction, event, ordering, retry, cancellation and idempotency cases.

- [ ] **Step 7: Commit recoverable planning and generation**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: generate hierarchical compaction candidates"
```

---

### Task 9: 原子发布 checkpoint 并支持接管恢复

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCheckpointPublicationTransaction.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionRecoveryService.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinator.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCheckpointPublicationIntegrationTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionRecoveryTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinatorTest.java`

**Interfaces:**
- Consumes: READY job, immutable candidate nodes, base checkpoint CAS identity and execution control.
- Produces: atomically published Cold/frontier/generation, terminal job and resumable coordinator outcome.

Define `PublishResult` as the private publication enum `PUBLISHED` or `STALE`, and `CompactionOutcome` as the coordinator result containing the published checkpoint plus target/hard-limit outcome flags. Keep both types inside the plugin runtime package.

- [ ] **Step 1: Write publication invariant tests**

Verify publication rejects missing nodes, gap/overlap, parent+child, pointer regression, stale checkpoint version/generation and owner/attempt mismatch. Verify successful publication updates Cold document, pointer, active frontier and `strategy_generation` in one transaction, then marks job `SUCCEEDED` and publishes `COMPLETED`.

- [ ] **Step 2: Write crash recovery tests for every durable boundary**

Cover takeover after `PLANNED`, midway through `GENERATING`, at `READY_TO_PUBLISH`, after checkpoint CAS before observing job success, and after fully succeeded publication. The new owner must reuse frozen policy, fixed input boundary and existing immutable nodes; old owner writes must fail.

- [ ] **Step 3: Run publication/recovery tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalCheckpointPublicationIntegrationTest,HierarchicalCompactionRecoveryTest,HierarchicalCompactionCoordinatorTest' test
```

Expected: compilation failure for missing publication and recovery services.

- [ ] **Step 4: Implement CAS publication in one new transaction**

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public PublishResult publish(ReadyCompaction ready) {
    executions.assertOwnedAndWithinBudget(ready.execution());
    invariants.validatePublishedView(ready);
    boolean published = checkpoints.compareAndSetCheckpoint(
            ready.expectedCheckpoint(),
            ready.replacementCheckpoint(),
            ready.execution());
    if (!published) {
        return PublishResult.STALE;
    }
    jobs.transitionJob(
            ready.jobId(), READY_TO_PUBLISH, SUCCEEDED,
            ready.execution(), null);
    executions.publish(
            ready.execution(),
            new ContextLifecycleEvent(COMPLETED, safeCompletedMetadata(ready)));
    return PublishResult.PUBLISHED;
}
```

Make checkpoint publication the only operation that exposes candidate nodes to normal reads.

- [ ] **Step 5: Implement job recovery and coordinator loop**

The recovery service must select the durable action from job status and current checkpoint generation. The coordinator sequence is plan-or-recover, generate missing candidates, publish, rebuild context and verify target/hard budget. A stale publication restarts from the newly published checkpoint; it must not overwrite it.

- [ ] **Step 6: Implement safe failure convergence**

After two summary failures or invalid publication, transition the job to `FAILED`, publish/fail with `CONTEXT_COMPACTION_FAILED`, and leave the current checkpoint unchanged. If fixed framework plus current turn exceeds hard limit, fail with `CONTEXT_WINDOW_EXCEEDED` without creating summary candidates.

- [ ] **Step 7: Run publication/recovery tests**

Run the command from Step 3.

Expected: PASS for publication, stale CAS, takeover, retry, cancellation and stable failure codes.

- [ ] **Step 8: Commit atomic publication and recovery**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: publish recoverable hierarchical context"
```

---

### Task 10: 注册 hierarchical 策略、自动配置和指标

**Files:**
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/strategy/HierarchicalConversationContextStrategy.java`
- Create: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/observability/HierarchicalContextMetrics.java`
- Modify: `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfiguration.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/strategy/HierarchicalConversationContextStrategyTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfigurationTest.java`
- Test: `backend/agent-context-hierarchical/src/test/java/com/datagent/agentv2/context/hierarchical/observability/HierarchicalContextMetricsTest.java`

**Interfaces:**
- Consumes: all plugin services plus existing shared SPI and ports.
- Produces: `strategyId=hierarchical` bean that is available only after plugin Flyway succeeds.

- [ ] **Step 1: Write strategy flow tests**

```java
@Test
void freezesPolicyBeforeAnyHistoryReadAndSkipsCompactionBelowTrigger() {
    InOrder order = inOrder(policies, assembler, coordinator);

    PreparedConversationContext prepared = strategy.prepare(request());

    order.verify(policies).findOrCreate(request().execution(), configuredPolicy);
    order.verify(assembler).loadAndEstimate(request(), frozenPolicy);
    verifyNoInteractions(coordinator);
    assertThat(prepared.compacted()).isFalse();
}
```

Add tests for over-75% compaction, published 70% target, protected minimum above 70%, hard-limit Hot degradation and current-turn hard failure.

- [ ] **Step 2: Extend auto-configuration tests**

Verify:

```text
strategy=raw          -> no hierarchical properties, Flyway, Mapper or strategy bean
strategy=lsm          -> no hierarchical properties, Flyway, Mapper or strategy bean
strategy=hierarchical -> plugin Flyway completes before strategy bean creation
```

Also verify the bean implements the existing `ConversationContextStrategy` and reports exactly `hierarchical`.

- [ ] **Step 3: Write metrics tests**

Record input tokens/utilization, compaction duration/outcome, generated node counts by level, active frontier counts, Cold pointer and strategy generation. Tags may contain strategy, outcome and level only; reject or omit tenant, session, message, summary and tool content.

- [ ] **Step 4: Run tests and confirm failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am `
  '-Dtest=HierarchicalConversationContextStrategyTest,HierarchicalContextAutoConfigurationTest,HierarchicalContextMetricsTest' test
```

Expected: compilation failure for missing strategy and metrics.

- [ ] **Step 5: Implement the strategy as the unique business entry**

```java
public PreparedConversationContext prepare(ConversationContextRequest request) {
    HierarchicalContextPolicySnapshot frozen =
            policies.findOrCreate(request.execution(), configuredPolicy);
    HierarchicalContextState state = assembler.loadAndEstimate(request, frozen);
    metrics.recordInput(state.estimatedTokens(), request.budget());
    if (!policy.requiresCompaction(state.estimatedTokens(), request.budget())) {
        return assembler.prepareCurrentView(state);
    }
    CompactionOutcome outcome = coordinator.compactIfRequired(state);
    return assembler.preparePublishedView(
            outcome.checkpoint(), request, frozen);
}
```

The policy freeze remains the first side effect.

- [ ] **Step 6: Wire all beans and migration dependency**

Annotate auto-configuration with the hierarchical condition, enable properties, import Mapper/Flyway configuration and define all repositories, planners, summary, runtime, metrics and strategy beans. Apply `@DependsOn("hierarchicalContextFlyway")` to the strategy bean.

- [ ] **Step 7: Run strategy/auto-configuration/metrics tests**

Run the command from Step 4.

Expected: PASS with no plugin activity under raw or lsm.

- [ ] **Step 8: Commit the plugin entry and observability**

```powershell
git add backend/agent-context-hierarchical
git commit -m "feat: register hierarchical context strategy"
```

---

### Task 11: 安装插件并验证策略切换、隔离和端到端恢复

**Files:**
- Modify: `backend/agent-server/pom.xml`
- Modify: `README.md`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalContextCompactionIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalContextCompactionRecoveryIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalContextCompactionMultiTenantIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/ContextStrategyIsolationIntegrationTest.java`

**Interfaces:**
- Consumes: `agent-context-hierarchical` auto-configuration and existing `ConversationContextStrategyResolver`.
- Produces: deployable strategy selection among raw, lsm and hierarchical with end-to-end proof.

- [ ] **Step 1: Write strategy isolation tests before installing the dependency**

Verify application contexts for each strategy:

```text
raw          -> selected raw; neither plugin migration executes
lsm          -> selected lsm; only LSM migration/table access occurs
hierarchical -> selected hierarchical; only hierarchical migration/table access occurs
unknown      -> startup fails with Unknown context strategy id
```

Verify duplicate strategy IDs fail startup.

- [ ] **Step 2: Write hierarchical end-to-end tests**

The main integration test must persist enough mixed Chinese/English/tool/thinking history to cross 75%, then assert:

- one STARTED and one COMPLETED event;
- resulting estimated input at or below 70%, unless protected minimum is explicitly recorded;
- latest 10 completed turns remain raw;
- Warm frontier covers all required turns without gap/overlap;
- Cold pointer advances monotonically after more than 100 turns;
- raw messages and thinking rows remain unchanged;
- normal model input preserves fixed framework and tool definitions.

- [ ] **Step 3: Write recovery and multi-tenant tests**

Crash at PLANNED, GENERATING and READY_TO_PUBLISH boundaries, perform lease takeover and assert convergence to one published generation. Verify old owner cannot publish. Create identical session IDs under distinct tenants and prove checkpoint/node/job/policy reads remain isolated.

- [ ] **Step 4: Run integration tests and confirm hierarchical is unavailable**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ContextStrategyIsolationIntegrationTest,HierarchicalContextCompactionIntegrationTest,HierarchicalContextCompactionRecoveryIntegrationTest,HierarchicalContextCompactionMultiTenantIntegrationTest' test
```

Expected: hierarchical context startup fails because `agent-server` does not yet depend on the plugin.

- [ ] **Step 5: Install the plugin in `agent-server`**

```xml
<dependency>
  <groupId>com.datagent</groupId>
  <artifactId>agent-context-hierarchical</artifactId>
  <version>${project.version}</version>
</dependency>
```

Do not add direct Java imports from `agent-server` to plugin implementation packages.

- [ ] **Step 6: Document configuration, migration and rollback**

Add `hierarchical` to `DATA_AGENT_CONTEXT_STRATEGY` allowed values. Document the four hierarchical properties, independent Flyway location/history, table prefix, strategy switching, retained data on rollback and an end-to-end Maven command. State that changing service configuration does not change an execution's frozen policy.

- [ ] **Step 7: Run hierarchical and existing LSM integration suites together**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ContextStrategyIsolationIntegrationTest,HierarchicalContextCompactionIntegrationTest,HierarchicalContextCompactionRecoveryIntegrationTest,HierarchicalContextCompactionMultiTenantIntegrationTest,LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest,LsmContextCompactionMultiTenantIntegrationTest' test
```

Expected: PASS for both independent plugins and raw fallback.

- [ ] **Step 8: Commit server installation and documentation**

```powershell
git add backend/agent-server README.md
git commit -m "feat: install hierarchical context plugin"
```

---

### Task 12: 完整验证和边界审计

**Files:**
- Modify only files required to fix failures caused by Tasks 1–11; do not perform unrelated cleanup.

**Interfaces:**
- Consumes: complete raw, lsm and hierarchical implementations.
- Produces: formatted, fully verified backend with no cross-plugin dependency or persistence leakage.

- [ ] **Step 1: Run architecture boundary searches**

Run:

```powershell
rg -n "context\.hierarchical" backend/agent-persistence
rg -n "context\.lsm" backend/agent-context-hierarchical
rg -n "context\.hierarchical" backend/agent-context-lsm
rg -n "agent-context-(lsm|hierarchical)" backend/agent-web/pom.xml
```

Expected: no matches. Also verify `agent-context-hierarchical/pom.xml` contains no `agent-web`, `agent-server` or `agent-context-lsm` dependency.

- [ ] **Step 2: Run formatting and focused module verification**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B `
  -pl agent-domain,agent-context-lsm,agent-context-hierarchical,agent-web,agent-server -am `
  spotless:apply test
```

Expected: PASS.

- [ ] **Step 3: Run the full supported backend verification**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

Expected: BUILD SUCCESS with all modules, Flyway tests and integration tests passing.

- [ ] **Step 4: Inspect repository boundaries and staged content**

Run:

```powershell
git status --short
git diff --check
git log --oneline --max-count=15
git -C .. status --short --ignore-submodules=none
```

Expected: only files belonging to the SPI refactor, LSM compatibility migration, new plugin, server integration and README are present. Root repository reports only the expected `data-agent-v2` Git link change.

- [ ] **Step 5: Commit verification-only fixes when needed**

If Step 2 or Step 3 required scoped fixes, stage only those files and commit:

```powershell
git add backend/agent-domain backend/agent-context-lsm `
  backend/agent-context-hierarchical backend/agent-web `
  backend/agent-server backend/pom.xml README.md
git commit -m "fix: complete hierarchical context verification"
```

If no files changed, do not create an empty commit.

---

## Completion Checklist

- [ ] Shared SPI contains no LSM or hierarchical policy fields.
- [ ] Existing LSM remains behaviorally identical and all LSM tests pass.
- [ ] `agent-context-hierarchical` is independently buildable and has no Web/server/LSM dependency.
- [ ] raw, lsm and hierarchical are selected exclusively by the existing resolver.
- [ ] Hierarchical plugin is inert and runs no Flyway when not selected.
- [ ] Hierarchical policy is frozen per execution before history reads or model calls.
- [ ] 75% trigger, 70% target, hard limit, Hot 10 and Warm 100 rules are covered by tests.
- [ ] Binary nodes and published frontier have no gap, overlap or parent/child duplication.
- [ ] Cold pointer, frontier and strategy generation publish atomically.
- [ ] Crash recovery and lease takeover converge without stale-owner publication.
- [ ] Plugin tables, Mapper, Repository, migration and schema remain inside the plugin module.
- [ ] Original message/thinking/tool data and fixed model framework remain unchanged.
- [ ] Focused module tests and full backend `verify` pass.
