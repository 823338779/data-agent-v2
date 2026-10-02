# Data Agent 受控数据分析 Workflow Implementation Plan

> 历史设计：相关 Workflow 或 Office 文件产出、沙箱实现已于 2026-10-02 移除，本文不再代表当前运行时能力。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有单实例会话执行模型中加入顶层执行路由，使数据请求进入受控 StarRocks 分析 Workflow，其他请求继续使用现有 ReAct，并通过现有 SSE 实时展示不持久化的 Workflow 过程和完整已验证 SQL。

**Architecture:** 新增 `agent-data-source` 和 `agent-workflow`。`agent-data-source` 负责单 StarRocks 连接、语义目录、SQL 安全与有界查询；`agent-workflow` 负责强类型路由、QueryPlan 和有限状态机；`agent-web` 负责模型适配、顶层策略选择、最终回答持久化及 SSE 桥接。Workflow 与 ReAct 是同级策略，Workflow 不注册为 Tool，也不新增持久化表。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring MVC SSE、Jackson 3 (`tools.jackson`)、HikariCP、StarRocks JDBC Driver、JSqlParser、JUnit 5、AssertJ、Mockito、React 19、TypeScript 5.8、Vitest。

**Spec:** `docs/superpowers/specs/2026-08-30-data-analysis-workflow-design.md`

## Global Constraints

- 只支持一个 StarRocks 数据源；不引入 `dataSourceId` 或运行期数据源选择。
- 数据 Workflow 与 `ReactExecutionEngine` 是同级执行策略，禁止把 Workflow 注册为 `AgentTool`。
- 使用结构化 `QueryPlan`，不引入 MQL。
- Workflow Run、Step、Event、完整 SQL 和查询结果不得写入数据库。
- 完整 SQL 只有在本地策略与 StarRocks `EXPLAIN` 都成功后才可通过 SSE 发送。
- 查询结果行不得通过 SSE、日志或持久化 Tool payload 暴露。
- `max-query-tasks` 默认 5，启动校验范围固定为 `1..20`。
- Workflow 总 deadline 不得超过现有 execution 剩余 deadline。
- 默认 Maven 测试不得访问真实 StarRocks；真实连接只由显式集成 Profile 使用。
- 新增和修改的核心代码、配置语义和复杂约束必须写充分的中文注释。
- 其他模块不得直接引用 Mapper 或 MyBatis API。
- 不修改现有认证/租户临时边界，继续使用固定 `TenantContext`/`OperatorContext`。
- 仓库规则禁止未经用户授权创建提交；本计划以“Review checkpoint”结束每个任务，不执行 `git commit`。

## File Structure

### 新增 `agent-data-source`

```text
backend/agent-data-source/
  pom.xml
  src/main/java/com/datagent/agentv2/datasource/config/
    StarRocksDataSourceProperties.java
    StarRocksDataSourceConfiguration.java
  src/main/java/com/datagent/agentv2/datasource/semantic/
    SemanticCatalog.java
    SemanticDataset.java
    SemanticField.java
    SemanticMetric.java
    SemanticRelationship.java
    SemanticResourceSet.java
    SemanticCatalogDocument.java
    SemanticCatalogLoader.java
    SemanticCatalogValidator.java
    SemanticResourceSearchService.java
    StarRocksMetadataReader.java
  src/main/java/com/datagent/agentv2/datasource/query/
    QueryAccessScope.java
    ValidatedSql.java
    SqlPolicyException.java
    SqlLexicalGuard.java
    StarRocksSqlPolicyValidator.java
    StarRocksQueryGateway.java
    ExplainResult.java
    ResultColumn.java
    TabularQueryResult.java
    QueryFailureCategory.java
    QueryFailureClassifier.java
```

### 新增 `agent-workflow`

```text
backend/agent-workflow/
  pom.xml
  src/main/java/com/datagent/agentv2/workflow/config/
    DataAnalysisWorkflowProperties.java
    DataAnalysisWorkflowConfiguration.java
  src/main/java/com/datagent/agentv2/workflow/model/
    ExecutionRoute.java
    QueryPlan.java
    QueryTask.java
    QueryFilter.java
    QueryTimeRange.java
    QueryOrder.java
    GeneratedSql.java
    AnalysisResult.java
    AnalysisFinding.java
    EvidenceRef.java
    QueryTaskResult.java
    RoutePrompt.java
    QueryPlanPrompt.java
    SqlGenerationPrompt.java
    ResultAnalysisPrompt.java
  src/main/java/com/datagent/agentv2/workflow/runtime/
    WorkflowRunRequest.java
    WorkflowRunContext.java
    WorkflowStep.java
    WorkflowStatus.java
    WorkflowEventType.java
    WorkflowEvent.java
    WorkflowEventDetail.java
    WorkflowTaskRef.java
    WorkflowSnapshot.java
    WorkflowTaskSnapshot.java
    WorkflowRunRegistry.java
    ExecutionRouteResolver.java
    QueryPlanValidator.java
    ResultVerifier.java
    WorkflowException.java
    WorkflowValidationException.java
    WorkflowCancelledException.java
    WorkflowDeadlineExceededException.java
    WorkflowFailureCode.java
    DataAnalysisWorkflowEngine.java
  src/main/java/com/datagent/agentv2/workflow/observability/
    DataAnalysisWorkflowMetrics.java
  src/main/java/com/datagent/agentv2/workflow/port/
    WorkflowModelPort.java
    WorkflowEventPort.java
    WorkflowFinalAnswerPort.java
    WorkflowExecutionControlPort.java
```

### `agent-web` 适配

```text
backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/
  WorkflowInputFactory.java
  AgentWorkflowModelAdapter.java
  WorkflowJsonDecoder.java
  WorkflowFinalAnswerAdapter.java
  WorkflowExecutionControlAdapter.java
  WorkflowSseEventPublisher.java
  RoutedAgentExecutionEngine.java
```

### 前端

```text
frontend/src/domain/agentPrototype.ts
frontend/src/api/backendAgentClient.ts
frontend/src/components/WorkflowRunPanel.tsx
frontend/src/App.tsx
frontend/src/styles.css
```

---

### Task 1: 建立语义目录与启动期 fail-fast 校验

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-data-source/pom.xml`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/config/StarRocksDataSourceProperties.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/config/StarRocksDataSourceConfiguration.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticCatalog.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticDataset.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticField.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticMetric.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticRelationship.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticResourceSet.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticCatalogDocument.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticCatalogLoader.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticCatalogValidator.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/SemanticResourceSearchService.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/StarRocksMetadataReader.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/semantic/SemanticCatalogLoaderTest.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/semantic/SemanticCatalogValidatorTest.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/semantic/SemanticResourceSearchServiceTest.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/config/StarRocksDataSourcePropertiesTest.java`
- Test resource: `backend/agent-data-source/src/test/resources/semantic/valid-starrocks.yaml`
- Test resource: `backend/agent-data-source/src/test/resources/semantic/invalid-field.yaml`

**Interfaces:**
- Produces: `SemanticCatalog`, `SemanticResourceSearchService.search(String question, int limit)`.
- Produces: validated single-datasource properties used by later SQL and query tasks.
- Consumes: JDBC `DataSource` only inside `agent-data-source`; no conversation or Workflow types.

- [ ] **Step 1: Add the module and dependency versions**

Add `<module>agent-data-source</module>` before `agent-workflow`/`agent-web` in `backend/pom.xml`, plus explicit properties:

```xml
<starrocks-jdbc.version>1.1.1</starrocks-jdbc.version>
<jsqlparser.version>5.3</jsqlparser.version>
```

Create `agent-data-source/pom.xml` with `spring-boot-starter-jdbc`, `jackson-dataformat-yaml`, `starrocks-connector-j`, and test dependencies. Do not add a dependency on `agent-web`, `agent-tool`, `agent-persistence`, or MyBatis.

- [ ] **Step 2: Write failing properties tests**

Test the exact defaults and invalid ranges:

```java
@Test
void rejectsMissingJdbcUrlAndNonPositiveLimits() {
    assertThatThrownBy(() -> StarRocksDataSourceProperties.checked(
            "", "user", "secret", "default_catalog", "analytics",
            Duration.ofSeconds(5), Duration.ofSeconds(30), 0, DataSize.ofMegabytes(4),
            "classpath:semantic/starrocks.yaml"))
        .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 3: Run the properties test and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am '-Dtest=*StarRocksDataSourcePropertiesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because the module and properties type do not exist.

- [ ] **Step 4: Implement validated configuration and Hikari wiring**

Use `@ConfigurationProperties("data-agent.datasource")`. The checked configuration must expose:

```java
public record StarRocksDataSourceProperties(
        String jdbcUrl,
        String username,
        String password,
        String defaultCatalog,
        String defaultDatabase,
        Duration connectTimeout,
        Duration queryTimeout,
        int maxRows,
        DataSize maxResultBytes,
        Resource semanticConfig) {}
```

`StarRocksDataSourceConfiguration` must create one Hikari pool, set `readOnly=true`, and validate connectivity before publishing `SemanticCatalog`. Never include the URL or password in validation exceptions.

- [ ] **Step 5: Write failing YAML and metadata validation tests**

Use a fake `StarRocksMetadataReader.MetadataSnapshot` so tests do not access a database:

```java
@Test
void rejectsYamlFieldThatDoesNotExistInMetadata() {
    SemanticCatalogDocument document = loader.load(resource("semantic/invalid-field.yaml"));
    assertThatThrownBy(() -> validator.validate(document, metadataWithOrders()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("orders.missing_field");
}
```

Also cover duplicate IDs, unknown metric fields, broken relationships, invalid roles, and type incompatibility.

- [ ] **Step 6: Implement immutable semantic types, loader, metadata reader, and validator**

Keep YAML DTOs separate from validated runtime records. `SemanticCatalog` must copy every collection and index datasets, fields, metrics, and relationships by stable ID. `StarRocksMetadataReader` reads catalog/database/table/column/type/comment through `DatabaseMetaData` or bounded `information_schema` queries; it must not accept model-provided identifiers.

- [ ] **Step 7: Write failing deterministic retrieval tests**

```java
@Test
void exactSynonymAndRelationshipResourcesRankBeforeDescriptionOnlyMatches() {
    SemanticResourceSet result = service.search("按地区查看销售额", 10);
    assertThat(result.metrics()).extracting(SemanticMetric::id).containsExactly("paid_sales");
    assertThat(result.datasets()).extracting(SemanticDataset::id).contains("orders");
    assertThat(result.fields()).extracting(SemanticField::id).contains("region");
}
```

- [ ] **Step 8: Implement deterministic semantic search**

Normalize whitespace and case, score exact business name, synonym, identifier, then description/comment. Expand only the owning dataset and declared relationship resources required by the selected metric/field. Apply the caller-provided positive limit after stable score/ID ordering.

- [ ] **Step 9: Run module tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am test
```

Expected: PASS; no network or real StarRocks access.

- [ ] **Step 10: Review checkpoint**

Inspect `git diff --check` and confirm the new module has no `agent-web`, Mapper, MyBatis, Tool, Skill, or persistence dependency. Do not commit without user authorization.

### Task 2: 实现只读 SQL 安全策略与不透明 ValidatedSql

**Files:**
- Modify: `backend/agent-data-source/pom.xml`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/QueryAccessScope.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/ValidatedSql.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/SqlPolicyException.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/SqlLexicalGuard.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/StarRocksSqlPolicyValidator.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/query/SqlLexicalGuardTest.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/query/StarRocksSqlPolicyValidatorTest.java`

**Interfaces:**
- Consumes: `QueryAccessScope` composed from validated semantic resources.
- Produces: `ValidatedSql`, constructible only by `StarRocksSqlPolicyValidator` inside the package.
- Produces stable policy reasons used by Workflow retry/error mapping.

- [ ] **Step 1: Write the malicious SQL rejection matrix first**

Parameterized tests must include:

```java
@ValueSource(strings = {
    "select * from fact_orders; drop table fact_orders",
    "select /*+ SET_VAR(query_timeout=9999) */ * from fact_orders",
    "select * from files('s3://bucket/data')",
    "show processlist",
    "set query_timeout = 9999",
    "insert into audit select * from fact_orders",
    "select * from secret_table"
})
void rejectsUnsafeOrUnauthorizedSql(String sql) {
    assertThatThrownBy(() -> validator.validate(sql, ordersScope()))
        .isInstanceOf(SqlPolicyException.class);
}
```

Positive tests must include one `SELECT`, one `WITH ... SELECT`, an approved JOIN, quoted identifiers, string literals containing `--`, and a server-rewritten LIMIT.

- [ ] **Step 2: Run the policy tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am '-Dtest=SqlLexicalGuardTest,StarRocksSqlPolicyValidatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because the policy types do not exist.

- [ ] **Step 3: Implement a quote-aware lexical guard**

`SqlLexicalGuard` must scan a single SQL string while tracking single quotes, double quotes, and backticks. Reject statement separators, line/block comments and unterminated literals only when outside a quoted literal. Do not use a raw `contains("--")` check that rejects legal string values.

- [ ] **Step 4: Implement access scope and opaque validated SQL**

```java
public record QueryAccessScope(
        Set<String> allowedTables,
        Map<String, Set<String>> allowedColumnsByTable,
        Set<AllowedJoin> allowedJoins,
        int maxRows) {
    public record AllowedJoin(
            String leftTable, String leftColumn, String rightTable, String rightColumn) {}
}

public final class ValidatedSql {
    private final String value;
    ValidatedSql(String value) { this.value = value; }
    public String value() { return value; }
}
```

Keep the constructor package-private so Workflow/model code cannot fabricate a validated query.

- [ ] **Step 5: Implement AST policy validation and LIMIT rewriting**

Use JSqlParser to require exactly one `Select`, traverse all tables/columns/joins, reject table functions and unapproved catalogs, and match joins against `AllowedJoin`. Rewrite a missing or excessive top-level LIMIT to `scope.maxRows()`. Re-parse the serialized SQL once after rewriting before constructing `ValidatedSql`.

- [ ] **Step 6: Run focused and full module tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am '-Dtest=*Sql*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am test
```

Expected: PASS.

- [ ] **Step 7: Review checkpoint**

Confirm no execution path accepts a raw SQL `String` after the policy boundary. Do not commit without user authorization.

### Task 3: 实现 StarRocks EXPLAIN、有界查询与错误分类

**Files:**
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/StarRocksQueryGateway.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/ExplainResult.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/ResultColumn.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/TabularQueryResult.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/QueryFailureCategory.java`
- Create: `backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/QueryFailureClassifier.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/query/StarRocksQueryGatewayTest.java`
- Test: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/query/QueryFailureClassifierTest.java`
- Create: `backend/agent-data-source/src/test/java/com/datagent/agentv2/datasource/query/StarRocksIntegrationIT.java`
- Modify: `backend/agent-data-source/pom.xml`

**Interfaces:**
- Consumes: `ValidatedSql`, query timeout, result row/byte limits.
- Produces: `ExplainResult`, `TabularQueryResult`, and stable failure categories.

- [ ] **Step 1: Write failing fake-JDBC tests**

Cover exact behavior:

```java
@Test
void explainRunsPlainExplainAndExecuteMarksRowTruncation() {
    gateway.explain(validated("select region, sum(amount) from fact_orders group by region limit 1000"));
    TabularQueryResult result = gateway.execute(validatedSql);

    verify(statement).executeQuery(startsWith("EXPLAIN "));
    assertThat(result.rows()).hasSize(1000);
    assertThat(result.truncated()).isTrue();
}
```

Also assert: `maxResultBytes` discards partial output and throws `query_result_too_large`; `Statement.setQueryTimeout` is called; interruption cancels Statement; URL/password never appear in mapped messages.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am '-Dtest=StarRocksQueryGatewayTest,QueryFailureClassifierTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because gateway types do not exist.

- [ ] **Step 3: Implement result types and streaming JDBC reader**

```java
public record TabularQueryResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        boolean truncated,
        long returnedRowCount,
        long elapsedMillis) {}
```

Read at most `maxRows + 1`; retain only `maxRows`. Track UTF-8 serialized size before adding each row. If byte limit would be exceeded, clear partial rows and fail. Normalize decimal, date/time, boolean, binary, and null explicitly.

- [ ] **Step 4: Implement authoritative EXPLAIN and failure classification**

`explain(ValidatedSql)` must execute only `EXPLAIN <sql>`, never `EXPLAIN ANALYZE`. `QueryFailureClassifier` maps vendor-neutral SQLState/class plus sanitized StarRocks categories to `SYNTAX_OR_SCHEMA`, `TRANSIENT_CONNECTION`, `TIMEOUT`, `RESULT_TOO_LARGE`, or `FATAL`; raw vendor text must not leave the module.

- [ ] **Step 5: Add opt-in integration profile**

Configure Failsafe under Maven profile `starrocks-it`; require `DATA_AGENT_STARROCKS_IT_JDBC_URL`, username, and password. `StarRocksIntegrationIT` creates no schema and only runs `SELECT 1`, `EXPLAIN SELECT 1`, timeout wiring, and metadata reads against caller-provided test resources.

- [ ] **Step 6: Run default verification**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-data-source -am test
```

Expected: PASS and `StarRocksIntegrationIT` is not executed.

- [ ] **Step 7: Review checkpoint**

Confirm only `ValidatedSql` reaches `explain`/`execute`, partial oversized results are never returned, and default tests are external-service free. Do not commit without user authorization.

### Task 4: 建立 Workflow 强类型模型、配置和运行期 registry

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-workflow/pom.xml`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/config/DataAnalysisWorkflowProperties.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/config/DataAnalysisWorkflowConfiguration.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/ExecutionRoute.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryPlan.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryTask.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryFilter.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryTimeRange.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryOrder.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/GeneratedSql.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/AnalysisResult.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/AnalysisFinding.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/EvidenceRef.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryTaskResult.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/RoutePrompt.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/QueryPlanPrompt.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/SqlGenerationPrompt.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/model/ResultAnalysisPrompt.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowStep.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowStatus.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowEventType.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowEvent.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowEventDetail.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowTaskRef.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowSnapshot.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowTaskSnapshot.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowRunRequest.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowRunContext.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowRunRegistry.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowException.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowValidationException.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowCancelledException.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowDeadlineExceededException.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/WorkflowFailureCode.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/port/WorkflowModelPort.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/port/WorkflowEventPort.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/port/WorkflowFinalAnswerPort.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/port/WorkflowExecutionControlPort.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/config/DataAnalysisWorkflowPropertiesTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/runtime/WorkflowRunRegistryTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/model/QueryPlanContractTest.java`

**Interfaces:**
- Consumes: semantic/query contracts from Tasks 1-3.
- Produces: Workflow/model/port types used by engine and `agent-web` adapters.

- [ ] **Step 1: Add module wiring and failing configuration tests**

Add `<module>agent-workflow</module>` after `agent-data-source`. The module depends on `agent-domain`, `agent-data-source`, and `micrometer-core`, not `agent-web`, `agent-tool`, `agent-skill`, or persistence.

Bind one immutable record with this exact field order:

```java
public record DataAnalysisWorkflowProperties(
        int maxQueryTasks,
        int maxQueryPlanAttempts,
        int maxSqlAttemptsPerTask,
        int maxTransientQueryRetries,
        DataSize maxGeneratedSqlBytes,
        int maxSemanticResources,
        DataSize maxAnalysisInputBytes,
        Duration hardTimeout) {}
```

Test defaults and hard bounds:

```java
assertThat(properties.maxQueryTasks()).isEqualTo(5);
assertThatThrownBy(() -> new DataAnalysisWorkflowProperties(
        21,
        3,
        3,
        1,
        DataSize.ofKilobytes(64),
        30,
        DataSize.ofKilobytes(512),
        Duration.ofSeconds(120)))
    .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run the new module tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-workflow -am test
```

Expected: FAIL because module types are absent.

- [ ] **Step 3: Implement exact Workflow contracts**

Use the signatures approved by the spec:

```java
public record QueryPlan(String analysisGoal, List<QueryTask> tasks) {}

public interface WorkflowModelPort {
    ExecutionRoute route(RoutePrompt prompt);
    QueryPlan buildQueryPlan(QueryPlanPrompt prompt);
    GeneratedSql generateSql(SqlGenerationPrompt prompt);
    AnalysisResult analyzeResults(ResultAnalysisPrompt prompt);
}

public record RoutePrompt(String question, List<String> recentTurns) {}

public record QueryPlanPrompt(
        String question, SemanticResourceSet resources, int maxQueryTasks) {}

public record SqlGenerationPrompt(
        QueryTask task,
        SemanticResourceSet resources,
        int maxRows,
        String previousSafeError) {}

public record ResultAnalysisPrompt(
        String analysisGoal, List<QueryTaskResult> taskResults, int maxInputBytes) {}

public record WorkflowRunRequest(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long inputUpToMessageSequence,
        String question,
        List<String> recentTurns,
        Instant executionDeadlineAt) {}

public interface WorkflowFinalAnswerPort {
    void complete(WorkflowRunRequest run, AnalysisResult analysis);
}

public interface WorkflowEventPort {
    void publish(WorkflowEvent event);
}

public interface WorkflowExecutionControlPort {
    void assertRunning(WorkflowRunRequest run, Instant workflowDeadline);
}
```

Every collection is defensively copied. Add Chinese field comments for IDs, units, attempts, null semantics, evidence row indexes, and lifecycle timestamps.

- [ ] **Step 4: Write and implement registry transition tests**

Tests must prove:

```java
WorkflowSnapshot opened = registry.open(request);
WorkflowEvent started = registry.transition(
        workflowId, STEP_STARTED, DISCOVER_SEMANTIC_RESOURCES, RUNNING, null, detail);
assertThat(started.sequence()).isEqualTo(1L);
assertThat(registry.snapshot("tenant", "session")).get().extracting(WorkflowSnapshot::currentStep)
    .isEqualTo(DISCOVER_SEMANTIC_RESOURCES);
registry.finish(workflowId, SUCCEEDED, WorkflowEventDetail.empty());
assertThat(registry.snapshot("tenant", "session")).isEmpty();
```

Reject duplicate execution opens, decreasing transitions, updates after terminal, cross-execution IDs, and buffers above the configured event cap.

- [ ] **Step 5: Implement bounded, non-persistent registry**

Use `ConcurrentHashMap<String, WorkflowRunState>` keyed by execution ID. Mutate each state under a per-run lock, increment one sequence, replace a complete snapshot, then publish the event outside the lock. `finish` must expose the terminal event before removing the state.

Keep one exact lifecycle API for later tasks:

```java
WorkflowSnapshot open(WorkflowRunRequest request);
WorkflowEvent transition(
        String workflowId,
        WorkflowEventType eventType,
        WorkflowStep step,
        WorkflowStatus status,
        WorkflowTaskRef task,
        WorkflowEventDetail detail);
WorkflowEvent finish(String workflowId, WorkflowStatus terminalStatus, WorkflowEventDetail detail);
Optional<WorkflowSnapshot> snapshot(String tenantId, String conversationSessionId);
boolean isActive(String executionId);
void close(String workflowId);
```

`finish` publishes through `WorkflowEventPort` and then removes the active snapshot; `close` is an idempotent failure-cleanup fallback and does not invent another terminal event.

- [ ] **Step 6: Run workflow contract tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-workflow -am test
```

Expected: PASS.

- [ ] **Step 7: Review checkpoint**

Use `rg` to prove no `Mapper`, MyBatis, `AgentTool`, `ReactExecutionEngine`, or `agent-web` references exist in the module. Do not commit without user authorization.

### Task 5: 实现 QueryPlan 校验、结果验证和有限状态机

**Files:**
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/QueryPlanValidator.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/ResultVerifier.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/ExecutionRouteResolver.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/DataAnalysisWorkflowEngine.java`
- Create: `backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/observability/DataAnalysisWorkflowMetrics.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/runtime/ExecutionRouteResolverTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/runtime/QueryPlanValidatorTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/runtime/ResultVerifierTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/runtime/DataAnalysisWorkflowEngineTest.java`
- Test: `backend/agent-workflow/src/test/java/com/datagent/agentv2/workflow/observability/DataAnalysisWorkflowMetricsTest.java`

**Interfaces:**
- Consumes: `WorkflowModelPort`, `SemanticResourceSearchService`, `StarRocksSqlPolicyValidator`, `StarRocksQueryGateway`, `WorkflowRunRegistry`, `WorkflowEventPort`, `WorkflowFinalAnswerPort`, `WorkflowExecutionControlPort`, `Clock`, and `DataAnalysisWorkflowMetrics`.
- Produces: one bounded synchronous `execute(WorkflowRunRequest)` operation.

- [ ] **Step 1: Write route and QueryPlan validator tests**

```java
@Test
void routeFailureFallsBackToGeneralReact() {
    when(model.route(any())).thenThrow(new IllegalStateException("invalid JSON"));
    assertThat(resolver.resolve(prompt)).isEqualTo(ExecutionRoute.GENERAL_REACT);
}

@Test
void rejectsTaskReferencingResourceOutsideSemanticSnapshot() {
    assertThatThrownBy(() -> validator.validate(planWithMetric("unknown"), resources, properties))
        .isInstanceOf(WorkflowValidationException.class)
        .hasMessageContaining("unknown");
}
```

- [ ] **Step 2: Write state-machine tests before implementation**

Cover at least:

1. one successful QueryTask;
2. exactly configured maximum tasks, executed sequentially;
3. invalid QueryPlan regenerated up to the limit;
4. SQL policy/EXPLAIN schema failure returns only to `GENERATE_SQL`;
5. transient connection retries identical `ValidatedSql` once;
6. timeout/result-too-large do not broaden or regenerate;
7. empty result reaches analysis;
8. analysis cannot append tasks;
9. cancellation/deadline stops before the next side effect;
10. any failure publishes exactly one terminal event and closes registry.

Add a parameterized error-mapping test covering every approved stable code:

```java
@EnumSource(WorkflowFailureCode.class)
void everyFailureCodeHasStableLowerSnakeCaseWireValue(WorkflowFailureCode code) {
    assertThat(code.value()).matches("[a-z][a-z0-9_]*");
}
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-workflow -am '-Dtest=ExecutionRouteResolverTest,QueryPlanValidatorTest,ResultVerifierTest,DataAnalysisWorkflowEngineTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because validators and engine are missing.

- [ ] **Step 4: Implement deterministic validators**

`QueryPlanValidator` must resolve every metric/dimension/filter/order/join from the immutable semantic snapshot and build `QueryAccessScope` for each task. `ResultVerifier` checks terminal result presence, serializable column/value types, truncation flags, and evidence addressability.

- [ ] **Step 5: Implement the explicit state machine**

Keep the main flow readable and finite:

```java
public void execute(WorkflowRunRequest request) {
    WorkflowRunContext context = open(request);
    try {
        discover(context);
        buildAndValidatePlan(context);
        for (QueryTask task : context.queryPlan().tasks()) {
            generateValidateExplainAndExecute(context, task);
        }
        verify(context);
        analyze(context);
        finalAnswers.complete(request, context.analysisResult());
        succeed(context);
    } catch (WorkflowCancelledException failure) {
        cancel(context, failure);
        throw failure;
    } catch (RuntimeException failure) {
        fail(context, failure);
        throw failure;
    } finally {
        registry.close(context.workflowId());
    }
}
```

Before each model/query/event side effect, compare the injected clock with `min(workflowDeadline, executionDeadline)` and check the cancellation port. Do not implement generic arbitrary graph traversal.

- [ ] **Step 6: Implement safe Workflow metrics**

`DataAnalysisWorkflowMetrics` wraps Micrometer and records total outcome, step duration, retries, query duration/rows and SQL policy denial. Allowed labels are fixed enums only: route, step, outcome, reason and truncated. Tests must inspect `SimpleMeterRegistry` and prove tenant/session/execution/workflow/task/SQL are absent from meter IDs and tags.

- [ ] **Step 7: Run workflow tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-workflow -am test
```

Expected: PASS.

- [ ] **Step 8: Review checkpoint**

Compare every transition in tests with the spec's allowed edge list. Do not commit without user authorization.

### Task 6: 接入结构化模型、顶层路由和最终回答

**Files:**
- Modify: `backend/agent-web/pom.xml`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/WorkflowInputFactory.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/WorkflowJsonDecoder.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/AgentWorkflowModelAdapter.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/WorkflowFinalAnswerAdapter.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/WorkflowExecutionControlAdapter.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/RoutedAgentExecutionEngine.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionLauncher.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/WorkflowInputFactoryTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/WorkflowJsonDecoderTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/AgentWorkflowModelAdapterTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/WorkflowFinalAnswerAdapterTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/WorkflowExecutionControlAdapterTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/workflow/RoutedAgentExecutionEngineTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionLauncherTest.java`

**Interfaces:**
- Consumes: `AgentModelGateway`, current context strategies, `ModelInvocationCoordinator`, `LocalExecutionGuard`, `DataAnalysisWorkflowEngine`, `ReactExecutionEngine`.
- Produces: `WorkflowModelPort`, `WorkflowFinalAnswerPort`, `WorkflowExecutionControlPort` adapters and one top-level execution engine.

- [ ] **Step 1: Add module dependencies and write routing tests**

Add `agent-workflow` to `agent-web/pom.xml` (it brings `agent-data-source` transitively). Test exact route selection:

```java
@Test
void dataRouteExecutesWorkflowWithoutReact() {
    when(routes.resolve(any())).thenReturn(DATA_ANALYSIS_WORKFLOW);
    engine.execute(run);
    verify(workflow).execute(any());
    verifyNoInteractions(react);
}

@Test
void generalRouteExecutesReactWithoutWorkflow() {
    when(routes.resolve(any())).thenReturn(GENERAL_REACT);
    engine.execute(run);
    verify(react).execute(run);
    verifyNoInteractions(workflow);
}
```

- [ ] **Step 2: Write strict JSON decoder/model adapter tests**

The decoder accepts one JSON document and rejects markdown fences, trailing prose, unknown enum values, duplicate task IDs, and missing required fields. The adapter always calls:

```java
model.complete(messages, List.of(), ignored -> {});
```

and rejects `ModelInvocationResult.ToolCalls` for every Workflow model node.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am '-Dtest=*Workflow*Test,RoutedAgentExecutionEngineTest,LocalAgentExecutionLauncherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because adapters and routed engine do not exist.

- [ ] **Step 4: Implement bounded Workflow input assembly**

`WorkflowInputFactory` reads the current `AgentExecution` deadline and user-visible messages up to `LocalAgentExecution.inputUpToMessageSequence()`, selects the last user message as `question`, and supplies a bounded recent turn list for routing. It must not include Tool payloads, thinking, JDBC configuration, or future messages.

- [ ] **Step 5: Implement prompts and strict structured decoding**

`AgentWorkflowModelAdapter` uses separate fixed Chinese system prompts and response schemas for route, QueryPlan, SQL and analysis. Include the exact allowed route values and instruct pure JSON output. Decode through Jackson into dedicated DTOs, reject unknown fields, then compose validated Workflow records through static factories.

- [ ] **Step 6: Implement final answer adapter without Tool capability**

`WorkflowFinalAnswerAdapter.complete(...)`:

1. reconstructs the current `LocalAgentExecution` scalars;
2. calls `ModelInvocationCoordinator.beginModelInvocation`;
3. prepares normal conversation history plus a fixed system message containing the bounded `AnalysisResult` and evidence;
4. calls `AgentModelGateway.complete(..., List.of(), coordinator::checkpoint)`;
5. rejects ToolCalls;
6. calls `completeFinalAnswer` for a final answer.

Do not pass Skill summaries or Tool definitions into this final invocation.

- [ ] **Step 7: Implement top-level routed engine and launcher replacement**

Implement `WorkflowExecutionControlAdapter.assertRunning(...)` by reconstructing the scalar `LocalAgentExecution`, calling `LocalExecutionGuard.assertRunningAndWithinDeadline(...)`, and rejecting a passed Workflow deadline. Change `LocalAgentExecutionLauncher` field from `ReactExecutionEngine` to `RoutedAgentExecutionEngine`. Keep Tool/Skill execution views open for compatibility with the general ReAct route; data Workflow must not read them.

- [ ] **Step 8: Run focused and module tests**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am '-Dtest=*Workflow*Test,RoutedAgentExecutionEngineTest,LocalAgentExecutionLauncherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am test
```

Expected: PASS, including existing ReAct/model/context tests.

- [ ] **Step 9: Review checkpoint**

Use `rg -n "data_analysis_workflow|implements AgentTool" backend/agent-workflow backend/agent-web` and confirm no high-level Workflow Tool exists. Do not commit without user authorization.

### Task 7: 接入 Workflow 内存 SSE、契约和终态关闭顺序

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/workflow/WorkflowSseEventPublisher.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ConversationStreamEventType.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java`
- Modify: `contracts/openapi.yaml`
- Modify: `contracts/schemas/session-sse-event.schema.json`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/stream/WorkflowEventStreamFacadeTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java`

**Interfaces:**
- Consumes: `WorkflowEventPort`, `WorkflowRunRegistry` snapshots and completion notifications.
- Produces: `workflow_snapshot` and `workflow_update` on the existing session SSE.

- [ ] **Step 1: Extend contract tests first**

Add schema examples and assertions for:

```json
{
  "type": "workflow_update",
  "conversationSessionId": "session-1",
  "executionId": "execution-1",
  "workflowId": "workflow-1",
  "sequence": 8,
  "eventType": "step_succeeded",
  "step": "EXPLAIN_SQL",
  "status": "RUNNING",
  "task": {
    "taskId": "regional-sales",
    "purpose": "统计地区销售额",
    "index": 1,
    "total": 2
  },
  "detail": {
    "sql": "SELECT region, SUM(amount) FROM fact_orders GROUP BY region LIMIT 1000"
  },
  "occurredAt": "2026-08-30T12:00:00Z"
}
```

The JSON Schema must forbid `rows`, `jdbcUrl`, `password`, `prompt`, and raw exception fields in Workflow detail.

- [ ] **Step 2: Write handshake and terminal-order tests**

Tests must prove:

- session snapshot is sent first;
- an active registry snapshot is sent second;
- events arriving during bootstrap are emitted after the snapshot without duplicates;
- only EXPLAIN-success events carry complete SQL;
- terminal execution/message updates do not close the emitter while registry is active;
- `workflow_succeeded` is queued before `completeAfterPending`;
- reconnect after registry cleanup has no Workflow snapshot.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am '-Dtest=WorkflowEventStreamFacadeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL because Workflow event support is absent.

- [ ] **Step 4: Implement event bridge and subscription handshake**

`WorkflowSseEventPublisher` implements `WorkflowEventPort` and publishes an in-process application event carrying tenant/session/execution identity plus the public event. `ConversationEventStreamFacade.open` must register the live listener before reading `WorkflowRunRegistry.snapshot(...)`, mirroring the existing subscribe-gap protection.

- [ ] **Step 5: Implement terminal close fencing**

Before `completeAfterPending`, check `workflowRuns.isActive(executionId)`. A registry completion notification must enqueue the terminal Workflow update, then allow the subscription to close. Never send `workflow_succeeded` before the final answer transaction returns successfully.

- [ ] **Step 6: Update OpenAPI and JSON Schema**

Add exact `WorkflowSnapshotEvent`, `WorkflowUpdateEvent`, `WorkflowSnapshot`, `WorkflowTaskSnapshot`, and detail schemas to `contracts/openapi.yaml`, and add both event types to `session-sse-event.schema.json` and `x-frontend-projection`.

- [ ] **Step 7: Run backend contract verification**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web,agent-server -am '-Dtest=WorkflowEventStreamFacadeTest,AgentV2ApplicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS.

- [ ] **Step 8: Review checkpoint**

Search serialized Workflow event records for forbidden sensitive fields. Confirm no Workflow state was added to REST `ConversationView` or persistence entities. Do not commit without user authorization.

### Task 8: 前端归约并实时展示 Workflow 与完整 SQL

**Files:**
- Modify: `frontend/src/domain/agentPrototype.ts`
- Modify: `frontend/src/api/backendAgentClient.ts`
- Modify: `frontend/src/api/backendAgentClient.test.ts`
- Create: `frontend/src/components/WorkflowRunPanel.tsx`
- Create: `frontend/src/components/WorkflowRunPanel.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: `workflow_snapshot` and `workflow_update` from Task 7.
- Produces: transient `ConversationSession.workflow` state and accessible live Workflow UI.

- [ ] **Step 1: Write reducer/listener tests first**

Extend `SESSION_EVENT_TYPES` tests to include both events. Add reducer tests:

```ts
it('replaces workflow from snapshot and ignores duplicate updates', () => {
  const withSnapshot = applyConversationStreamEvent(session, workflowSnapshotEvent);
  const advanced = applyConversationStreamEvent(withSnapshot, workflowUpdateSequence2);
  const duplicate = applyConversationStreamEvent(advanced, workflowUpdateSequence2);
  expect(duplicate.workflow?.latestSequence).toBe(2);
  expect(duplicate.workflow?.tasks[0].sql).toContain('SELECT');
});
```

Also reject another session/execution, stale sequence, and sequence gaps. A later `workflow_snapshot` must recover a gap.

- [ ] **Step 2: Run frontend tests and verify failure**

Run:

```powershell
npm --prefix .\frontend test
```

Expected: FAIL because Workflow types/listeners do not exist.

- [ ] **Step 3: Add domain types and event mapping**

Add:

```ts
export interface WorkflowSnapshot {
  workflowId: string;
  executionId: string;
  latestSequence: number;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT';
  currentStep: WorkflowStep;
  tasks: WorkflowTaskSnapshot[];
  startedAt: string;
  updatedAt: string;
}
```

Add `workflow?: WorkflowSnapshot | null` to `ConversationSession`. REST/session snapshot mapping initializes it to null; only Workflow SSE events populate it.

- [ ] **Step 4: Implement idempotent Workflow reducer**

`workflow_snapshot` replaces same-execution transient state when its sequence is not older. `workflow_update` applies only to the matching execution and the next sequence. Terminal events remain in React memory for the current page but are absent after a REST refresh.

- [ ] **Step 5: Write component tests**

Assert accessible labels for current step, task `1/2`, retry attempt, returned rows, truncation, failure, and complete SQL. SQL must render in a horizontally scrollable `<pre><code>` block and never be interpreted as Markdown/HTML.

- [ ] **Step 6: Implement WorkflowRunPanel and App integration**

Render the panel for `active.workflow` above the active assistant `RunStreamPanel`. Keep existing message ProcessItems unchanged. Use `aria-live="polite"` for status text, not for the full SQL block, to avoid repeatedly reading large SQL to screen readers.

- [ ] **Step 7: Run frontend verification**

Run:

```powershell
npm --prefix .\frontend test
npm --prefix .\frontend run build
```

Expected: PASS.

- [ ] **Step 8: Review checkpoint**

Confirm a page refresh removes transient Workflow details while retaining the final assistant answer. Do not commit without user authorization.

### Task 9: 完成服务级闭环、测试配置、边界检查和运维文档

**Files:**
- Modify: `backend/agent-server/pom.xml`
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Create: `backend/agent-server/src/main/resources/semantic/starrocks.yaml`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/server/ModuleBoundaryTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/DataAnalysisWorkflowIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/support/TestDataWorkflowConfiguration.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ConversationSummarySseIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/AgentExecutionSubmissionIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/OllamaLsmLongContextIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/HierarchicalFullHistoryIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/context/ContextPluginActivationIntegrationTest.java`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-30-data-analysis-workflow-design.md` only if implementation reveals an approved factual correction
- Create: `backend/agent-evals/src/test/resources/data-workflow/smoke-cases.json`
- Create: `backend/agent-evals/src/test/java/com/datagent/agentv2/evals/workflow/DataWorkflowSmokeEvalTest.java`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: bootable application configuration, end-to-end test, boundary enforcement, eval smoke corpus, and operator documentation.

- [ ] **Step 1: Add production configuration without credentials**

Add only environment placeholders:

```yaml
data-agent:
  datasource:
    jdbc-url: ${DATA_AGENT_STARROCKS_JDBC_URL}
    username: ${DATA_AGENT_STARROCKS_USERNAME}
    password: ${DATA_AGENT_STARROCKS_PASSWORD}
    default-catalog: ${DATA_AGENT_STARROCKS_CATALOG:default_catalog}
    default-database: ${DATA_AGENT_STARROCKS_DATABASE:analytics}
    connect-timeout: ${DATA_AGENT_STARROCKS_CONNECT_TIMEOUT:5s}
    query-timeout: ${DATA_AGENT_STARROCKS_QUERY_TIMEOUT:30s}
    max-rows: ${DATA_AGENT_STARROCKS_MAX_ROWS:1000}
    max-result-bytes: ${DATA_AGENT_STARROCKS_MAX_RESULT_BYTES:4MB}
    semantic-config: classpath:semantic/starrocks.yaml
  workflow:
    max-query-tasks: ${DATA_AGENT_WORKFLOW_MAX_QUERY_TASKS:5}
    max-query-plan-attempts: ${DATA_AGENT_WORKFLOW_MAX_PLAN_ATTEMPTS:3}
    max-sql-attempts-per-task: ${DATA_AGENT_WORKFLOW_MAX_SQL_ATTEMPTS:3}
    max-transient-query-retries: ${DATA_AGENT_WORKFLOW_MAX_TRANSIENT_RETRIES:1}
    max-generated-sql-bytes: ${DATA_AGENT_WORKFLOW_MAX_SQL_BYTES:64KB}
    max-semantic-resources: ${DATA_AGENT_WORKFLOW_MAX_SEMANTIC_RESOURCES:30}
    max-analysis-input-bytes: ${DATA_AGENT_WORKFLOW_MAX_ANALYSIS_BYTES:512KB}
    hard-timeout: ${DATA_AGENT_WORKFLOW_TIMEOUT:120s}
```

Do not place a sample password, API key, reachable host, or connection string in tracked files.
Because this task already edits `application-business.yml`, also normalize the existing web-search credential entry to `api-key: ${DATA_AGENT_TAVILY_API_KEY:}` if it is not already environment-backed; never preserve or copy a literal credential into the diff.

- [ ] **Step 2: Make unrelated server tests external-service free**

Replace mocks of `ReactExecutionEngine` with `RoutedAgentExecutionEngine` where the test only verifies REST/persistence behavior. Supply fake semantic catalog/query beans or a dedicated test configuration so Spring context tests do not contact StarRocks. Keep one separate startup-failure test proving missing/invalid production datasource settings fail fast.

- [ ] **Step 3: Write the end-to-end test first**

`DataAnalysisWorkflowIntegrationTest` must use fake `WorkflowModelPort` and fake StarRocks gateway to assert:

```text
POST user message
-> route DATA_ANALYSIS_WORKFLOW
-> workflow_started/step events over SSE
-> full validated SQL after fake EXPLAIN
-> no rows in SSE
-> assistant_delta
-> persisted final assistant message
-> execution DONE
-> reconnect has no workflow_snapshot
```

- [ ] **Step 4: Run end-to-end test and fix only integration gaps**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=DataAnalysisWorkflowIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS after wiring all adapters.

- [ ] **Step 5: Extend module boundary tests**

Assert:

- `agent-data-source` has no web/tool/skill/persistence/MyBatis references;
- `agent-workflow` has no web/tool/skill/persistence/MyBatis references;
- persistence does not reference either new runtime implementation module;
- only `agent-server` declares `@SpringBootApplication`.

- [ ] **Step 6: Add eval smoke cases**

Include at least: single metric, dimension breakdown, two-period comparison, multi-query root-cause analysis, unknown metric, unsafe SQL regeneration, empty result, and truncated result. The eval test uses fakes and asserts route, task count, evidence presence, and stable outcome; it must not become a runtime dependency.

- [ ] **Step 7: Document operation and security**

Update `README.md` in Chinese with required environment variables, YAML semantics, fail-fast startup, single datasource limitation, SQL visibility in SSE, non-persistence after refresh, opt-in StarRocks integration Profile, and exact verification commands.

- [ ] **Step 8: Run complete backend and frontend verification**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B test
npm --prefix .\frontend test
npm --prefix .\frontend run build
```

Expected: all modules and frontend checks PASS.

- [ ] **Step 9: Run architectural and secret checks**

Run:

```powershell
rg -n "org\.apache\.ibatis|com\.baomidou|Mapper" .\backend\agent-data-source\src .\backend\agent-workflow\src
rg -n "data_analysis_workflow|implements AgentTool" .\backend\agent-workflow .\backend\agent-web
rg -n "jdbc:starrocks://|password:\s*[^$]|api[_-]?key:\s*[^$]" .\backend\agent-server\src\main .\README.md
git diff --check
git status --short
```

Expected: architecture searches return no violations; secret search finds only documented placeholder names, never literal values; diff check is clean.

- [ ] **Step 10: Final review checkpoint**

Compare the implementation against all 13 acceptance criteria in the spec. Record the exact test outputs and any intentionally deferred opt-in StarRocks integration result. Do not commit, branch, or push without explicit user authorization.

## Execution Notes

- Execute tasks in order. Tasks 1-3 establish the only path to StarRocks; Tasks 4-5 establish a fully fake-testable Workflow; Task 6 connects execution/model boundaries; Task 7 adds transient transport; Task 8 makes the transport visible; Task 9 proves the full system.
- Run the smallest focused test before each module-wide test. Do not wait until Task 9 to discover package or interface drift.
- If JSqlParser cannot reliably analyze an approved StarRocks syntax, keep the first release's SQL subset conservative and add a failing policy test before widening it. Never bypass AST validation by trusting `EXPLAIN` alone.
- If the existing final-answer transaction closes SSE before the Workflow terminal event, implement the registry-active close fence from Task 7; do not reorder `workflow_succeeded` before transaction success.
- Any change that introduces Workflow persistence, multiple data sources, parallel tasks, arbitrary DAGs, high-level Workflow Tools, or human approval is outside this plan and requires a new approved specification.
