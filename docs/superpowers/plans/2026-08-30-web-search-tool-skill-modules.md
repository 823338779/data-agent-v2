# 联网搜索与 Tool/Skill 独立模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立 `agent-tool` 与 `agent-skill` Maven Module，以 Tavily 实现正式联网搜索，并让 `web_search` 只在读取 bundled `web-research` Skill 后于本次 execution 内受限激活。

**Architecture:** `agent-tool` 拥有 Tool SPI、全局供给、execution 级 `maximum/visible` 视图和具体 Tool；`agent-skill` 依赖 `agent-tool`，拥有 bundled Skill 解析、execution Skill 快照及 `skill_read`。`agent-web` 只保留 ReAct、Tool 执行编排、ProcessItem 持久化和模型协议映射；本机 execution 启动时打开内存能力视图，任意终态在 `finally` 中清理。

**Tech Stack:** Java 21、Maven Reactor、Spring Boot 4.1、Jackson 3（`tools.jackson`）、JDK `HttpClient`、JUnit 5、AssertJ、Mockito。

**Spec:** `docs/superpowers/specs/2026-08-30-web-search-tool-skill-modules-design.md`

## Global Constraints

- 首版只支持 Tavily，固定请求 `POST https://api.tavily.com/search`，不得提供可配置 Endpoint。
- 配置仅来自环境变量或 `application.yml`；不新增数据库表、Mapper、管理 API 或前端页面。
- `visible ⊆ maximum ⊆ registeredSupply`；Skill 只能原子激活已位于 `maximum` 的依赖 Tool。
- `web_search` 默认隐藏；`system_timezone`、`current_time`、`skill_read` 默认初始可见。
- API Key 不得进入模型 Schema、Tool 输出、异常、`toString()`、日志或测试快照。
- Skill 只允许读取当前 classpath 包内 UTF-8 文本；禁止上传、脚本执行、嵌套 Skill 和路径穿越。
- 新增或修改的核心代码使用充分且准确的中文类注释、字段注释和并发/原子性说明。
- `agent-tool`、`agent-skill` 不得依赖 `agent-web`、Mapper 或 MyBatis API。
- 生产行为严格执行 RED → GREEN → REFACTOR；每个任务先确认测试因目标能力缺失而失败。
- 不调用真实 Tavily，不产生 API credits；HTTP 集成测试使用本机 Stub Server。
- 不修改或提交任务范围之外的用户文件。

---

## 文件结构总览

### 新增 Module：`backend/agent-tool`

- `pom.xml`：Tool Module 依赖。
- `src/main/java/com/datagent/agentv2/conversation/tool/AgentTool.java`：稳定 Tool SPI，从 `agent-web` 迁入且保持包名兼容。
- `src/main/java/com/datagent/agentv2/conversation/exception/ToolBusinessException.java`：安全业务异常，从 `agent-web` 迁入。
- `src/main/java/com/datagent/agentv2/conversation/tool/supply/ToolBinding.java`：名称、definition digest、实现绑定。
- `src/main/java/com/datagent/agentv2/conversation/tool/supply/ToolSupplyRegistry.java`：全局 Tool 供给和重名检测。
- `src/main/java/com/datagent/agentv2/conversation/tool/runtime/ToolPolicyProperties.java`：`maximum/initial` 服务配置。
- `src/main/java/com/datagent/agentv2/conversation/tool/runtime/ToolActivationResult.java`：原子激活结果。
- `src/main/java/com/datagent/agentv2/conversation/tool/runtime/ExecutionToolView.java`：单次 execution 冻结视图。
- `src/main/java/com/datagent/agentv2/conversation/tool/runtime/ExecutionToolViews.java`：内存视图生命周期。
- `src/main/java/com/datagent/agentv2/conversation/tool/builtin/SystemTimezoneTool.java`：从 `agent-web` 迁入。
- `src/main/java/com/datagent/agentv2/conversation/tool/builtin/CurrentTimeTool.java`：从 `agent-web` 迁入。
- `src/main/java/com/datagent/agentv2/conversation/tool/builtin/WebSearchTool.java`：正式通用搜索 Tool façade。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProperties.java`：Tavily 与 timeout/代理配置。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/SearchDepth.java`：严格深度枚举。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchQuery.java`：供应商无关查询。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchResult.java`：供应商无关结果。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProvider.java`：Provider SPI。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProviderRegistry.java`：单 Provider 路由与未知值拒绝。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchException.java`：内部分类异常。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchTelemetry.java`：不含查询和凭据的观测边界。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchTelemetryEvent.java`：Provider、depth、耗时、结果和稳定结果码。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/Slf4jWebSearchTelemetry.java`：安全结构化日志实现。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchHttpRequest.java`：覆盖 `toString()` 的安全请求对象。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchHttpResponse.java`：状态、Headers 和 Body。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchTransport.java`：窄 HTTP 边界。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/transport/JdkWebSearchTransport.java`：JDK HTTP 实现。
- `src/main/java/com/datagent/agentv2/conversation/tool/web/tavily/TavilyWebSearchProvider.java`：Tavily Adapter。

### 新增 Module：`backend/agent-skill`

- `pom.xml`：Skill Module 依赖 `agent-tool`。
- `src/main/java/com/datagent/agentv2/skill/SkillDescriptor.java`：Skill 元数据。
- `src/main/java/com/datagent/agentv2/skill/SkillBundle.java`：描述、正文、资源和 digest。
- `src/main/java/com/datagent/agentv2/skill/SkillFrontmatterParser.java`：严格最小 frontmatter 解析。
- `src/main/java/com/datagent/agentv2/skill/SkillResourceSource.java`：资源来源测试缝。
- `src/main/java/com/datagent/agentv2/skill/ClasspathSkillResourceSource.java`：classpath 扫描与读取。
- `src/main/java/com/datagent/agentv2/skill/BundledSkillCatalog.java`：只读 bundled 目录。
- `src/main/java/com/datagent/agentv2/skill/SkillCatalogStartupValidator.java`：Skill/Tool 交叉引用启动校验。
- `src/main/java/com/datagent/agentv2/skill/runtime/ExecutionSkillView.java`：单次 execution Skill 摘要快照。
- `src/main/java/com/datagent/agentv2/skill/runtime/ExecutionSkillViews.java`：Skill 视图生命周期和摘要 prompt。
- `src/main/java/com/datagent/agentv2/skill/tool/SkillReadTool.java`：读取资源并原子激活 Tool。
- `src/main/resources/skills/web-research/SKILL.md`：内置联网研究 Skill。

### 修改 Module：`backend/agent-web`

- `pom.xml`：依赖 `agent-tool`、`agent-skill`，移除仅为旧 WebSearch 使用的实现依赖。
- `conversation/tool/ToolExecutionCoordinator.java`：按 execution 可见视图解析 Tool。
- `conversation/tool/ToolFailureSanitizer.java`：继续映射迁移后的安全业务异常。
- `conversation/react/ReactExecutionEngine.java`：每轮读取当前 visible Tool。
- `conversation/react/ToolModelDefinitionMapper.java`：中立 Tool 到模型协议映射。
- `conversation/context/ConversationContextRequestFactory.java`：注入 Skill 摘要 system message。
- `conversation/context/RawConversationContextStrategy.java`：保留 fixed system messages。
- `conversation/runtime/LocalAgentExecutionLauncher.java`：打开并在 `finally` 清理两类能力视图。
- 删除旧 `AgentTool.java`、`AgentToolRegistry.java`、`ToolBusinessException.java` 和三个 `builtin` Tool 源文件。

### 修改 Module：`backend/agent-server`

- `AgentV2Application.java`：配置属性扫描扩展到 `com.datagent.agentv2`。
- `src/main/resources/application-business.yml`：新增 Tool 策略和 Tavily 配置。
- Spring 上下文测试：验证新 Module 装配。

---

### Task 1: 建立 `agent-tool` Module 并迁移稳定 Tool SPI

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-tool/pom.xml`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/AgentTool.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/exception/ToolBusinessException.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/AgentTool.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/exception/ToolBusinessException.java`
- Modify: `backend/agent-web/pom.xml`
- Create: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/AgentToolContractTest.java`

**Interfaces:**
- Produces: `AgentTool` with `name()`, `description()`, `parametersSchema()`, `timeout()`, `supportsIdempotency()`, `execute(JsonNode, InvocationContext)`.
- Produces: `ToolBusinessException(String code, String safeMessage)`.
- Preserves: existing fully qualified names, so `agent-web` consumers do not require package rewrites.

- [ ] **Step 1: Add reactor and dependency assertions that fail before the Module exists**

Create `AgentToolContractTest` with a safe-message invariant:

```java
package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import org.junit.jupiter.api.Test;

class AgentToolContractTest {

    @Test
    void businessFailureExposesOnlyStableCodeAndSafeMessage() {
        ToolBusinessException failure =
                new ToolBusinessException("web_search_unavailable", "Search is unavailable");

        assertThat(failure.code()).isEqualTo("web_search_unavailable");
        assertThat(failure.safeMessage()).isEqualTo("Search is unavailable");
        assertThat(failure.getMessage()).isEqualTo("Search is unavailable");
    }
}
```

- [ ] **Step 2: Run the new Module test and confirm RED**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool test
```

Expected: Maven fails because `agent-tool` is not yet a reactor Module.

- [ ] **Step 3: Add `agent-tool`, move the two contracts, and wire `agent-web`**

Add `<module>agent-tool</module>` before `agent-web`. `agent-tool/pom.xml` must depend only on `spring-boot-starter-jackson`, `spring-context`, and `spring-boot-starter-test` in test scope. Add this dependency to `agent-web/pom.xml`:

```xml
<dependency>
  <groupId>com.datagent</groupId>
  <artifactId>agent-tool</artifactId>
  <version>${project.version}</version>
</dependency>
```

Move the existing source bodies without changing public methods or package names. Do not change `ToolExecutionCoordinator` in this task.

- [ ] **Step 4: Run focused and downstream tests for GREEN**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool,agent-web -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=AgentToolContractTest,ToolExecutionCoordinatorTest' test
```

Expected: both tests pass; `agent-tool` has no dependency on `agent-web`.

- [ ] **Step 5: Commit**

```powershell
git add backend/pom.xml backend/agent-tool backend/agent-web/pom.xml `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/AgentTool.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/exception/ToolBusinessException.java
git commit -m "refactor: extract agent tool contracts"
```

---

### Task 2: 实现 Tool 供给、绑定 fingerprint 与 execution 可见视图

**Files:**
- Modify: `backend/agent-tool/pom.xml`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/supply/ToolBinding.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/supply/ToolSupplyRegistry.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/runtime/ToolPolicyProperties.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/runtime/ToolActivationResult.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/runtime/ExecutionToolView.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/runtime/ExecutionToolViews.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/supply/ToolSupplyRegistryTest.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/runtime/ExecutionToolViewsTest.java`

**Interfaces:**
- Produces: `ToolBinding(String runtimeName, String fingerprint, AgentTool tool)`.
- Produces: `ToolSupplyRegistry.require(String)`, `names()`, `bindingsInRegistrationOrder()`.
- Produces: `ExecutionToolViews.open(String)`, `require(String)`, `close(String)`, `contains(String)`.
- Produces: `ExecutionToolView.visibleTools()`, `requireVisible(String)`, `activateAll(Collection<String>)`, `maximumNames()`, `visibleNames()`.
- Produces: `ToolActivationResult(List<String> activatedToolNames, boolean alreadyActivated)`；`activateAll` 返回该类型。

- [ ] **Step 1: Write failing supply and atomic activation tests**

Core assertions:

```java
@Test
void rejectsDuplicateRuntimeNames() {
    AgentTool first = tool("duplicate");
    AgentTool second = tool("duplicate");

    assertThatThrownBy(() -> new ToolSupplyRegistry(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Duplicate agent tool: duplicate");
}

@Test
void activationIsAllOrNoneWhenAnyToolIsOutsideMaximum() throws Exception {
    ToolSupplyRegistry supply =
            new ToolSupplyRegistry(List.of(tool("skill_read"), tool("web_search"), tool("secret")));
    ToolPolicyProperties policy =
            new ToolPolicyProperties(List.of("skill_read", "web_search"), List.of("skill_read"));
    ExecutionToolViews views = new ExecutionToolViews(supply, policy);
    views.open("execution-1");

    assertThatThrownBy(
                    () ->
                            views.require("execution-1")
                                    .activateAll(List.of("web_search", "secret")))
            .isInstanceOf(ToolBusinessException.class)
            .satisfies(
                    failure ->
                            assertThat(((ToolBusinessException) failure).code())
                                    .isEqualTo("skill_activation_denied"));
    assertThat(views.require("execution-1").visibleNames())
            .containsExactly("skill_read");
}
```

Also test `initial ⊄ maximum`, unknown configured names, hidden `requireVisible`, duplicate `open`, idempotent activation, stable registration order, and `close` cleanup.

- [ ] **Step 2: Run tests and confirm RED**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool `
  '-Dtest=ToolSupplyRegistryTest,ExecutionToolViewsTest' test
```

Expected: compilation fails because the supply and runtime types do not exist.

- [ ] **Step 3: Implement deterministic binding fingerprints**

Add `spring-boot-autoconfigure` to `agent-tool/pom.xml` for `@ConfigurationProperties` and `@DefaultValue`; keep the Module free of WebMVC, JDBC and persistence dependencies.

`ToolBinding` validates nonblank values and non-null Tool. `ToolSupplyRegistry` indexes in Spring injection order and computes SHA-256 from the following exact UTF-8 sequence:

```text
runtimeName + "\n"
+ implementationClassName + "\n"
+ description + "\n"
+ parametersSchema.toString()
```

Use lowercase hexadecimal output. The digest contains no API Key or runtime configuration.

In the test class, define `tool(String name)` as a private `AgentTool` whose description is the name, schema is an empty object, and `execute` returns an empty object. This helper is test-only and must not enter production code.

- [ ] **Step 4: Implement policy validation and execution view**

Use this property contract:

```java
@ConfigurationProperties("data-agent.tool")
public record ToolPolicyProperties(
        @DefaultValue({"system_timezone", "current_time", "skill_read", "web_search"})
                List<String> maximum,
        @DefaultValue({"system_timezone", "current_time", "skill_read"})
                List<String> initial) {}
```

Normalize with insertion-order deduplication but reject duplicates instead of silently dropping them. `ExecutionToolView.activateAll` must validate all names and current fingerprints before entering the mutation that adds all names. On first activation it returns the newly added names with `alreadyActivated=false`; when every requested name is already visible it returns the same ordered names with `alreadyActivated=true`. `requireVisible` throws:

```java
new ToolBusinessException("tool_not_visible", "Tool is not visible for this execution")
```

Do not include the hidden runtime name in the safe message.

- [ ] **Step 5: Run focused tests for GREEN and format**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool spotless:apply
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool test
```

Expected: all `agent-tool` tests pass after formatting.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-tool
git commit -m "feat: add execution scoped tool visibility"
```

---

### Task 3: 将时区和时间 Tool 迁入 `agent-tool`

**Files:**
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/builtin/SystemTimezoneTool.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/builtin/CurrentTimeTool.java`
- Delete: matching files under `backend/agent-web/src/main/java/.../tool/builtin/`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/builtin/SystemTimezoneToolTest.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/builtin/CurrentTimeToolTest.java`

**Interfaces:**
- Preserves runtime names `system_timezone` and `current_time` and their existing JSON output contracts.
- Preserves package names so downstream source imports remain stable.

- [ ] **Step 1: Add fixed-clock behavior tests in `agent-tool`**

```java
@Test
void currentTimeUsesOneClockSnapshotForAllFields() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-08-30T01:02:03Z"), ZoneId.of("Asia/Shanghai"));
    CurrentTimeTool tool = new CurrentTimeTool(clock);

    JsonNode result = tool.execute(JsonMapper.builder().build().createObjectNode(), context());

    assertThat(result.path("instant").asString()).isEqualTo("2026-08-30T01:02:03Z");
    assertThat(result.path("localDateTime").asString()).isEqualTo("2026-08-30T09:02:03");
    assertThat(result.path("zoneId").asString()).isEqualTo("Asia/Shanghai");
    assertThat(result.path("offset").asString()).isEqualTo("+08:00");
}
```

Add an equivalent fixed-clock assertion for `SystemTimezoneTool` and assert both schemas reject additional model arguments by declaring `additionalProperties=false`.

- [ ] **Step 2: Run tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool `
  '-Dtest=SystemTimezoneToolTest,CurrentTimeToolTest' test
```

Expected: compilation fails because the concrete Tool classes are still owned by `agent-web`.

- [ ] **Step 3: Move implementations without behavior changes**

Recreate the existing two source files in `agent-tool` and delete the `agent-web` copies in the same patch. Preserve the package-private fixed-clock constructors used by tests and all existing Chinese comments.

- [ ] **Step 4: Run Module and downstream tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool,agent-web -am test
```

Expected: both modules pass and Spring has only one bean for each runtime Tool.

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-tool backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/builtin
git commit -m "refactor: move built in tools to agent tool"
```

---

### Task 4: 建立联网搜索领域契约、配置和 Tool façade

**Files:**
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/SearchDepth.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchQuery.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchResult.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProvider.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProviderRegistry.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchException.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProperties.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/builtin/WebSearchTool.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/builtin/WebSearchTool.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/builtin/WebSearchToolTest.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/web/WebSearchPropertiesTest.java`

**Interfaces:**
- Produces: `WebSearchProvider.id()` and `search(WebSearchQuery)`.
- Produces: `WebSearchQuery(String query, int limit, SearchDepth depth)` with no credentials.
- Produces: `WebSearchResult(String title, String url, String snippet, Double score)`；URL 校验属于 Tool façade，单条上游脏 URL 不得让整个 Provider 响应失败。
- Produces: `WebSearchTool` runtime name `web_search` and input fields `query`, `limit`, `search_depth` only.

- [ ] **Step 1: Write failing façade contract tests**

Use a recording Provider:

```java
@Test
void invocationOverridesConfiguredLimitAndDepth() throws Exception {
    RecordingProvider provider =
            new RecordingProvider(
                    List.of(
                            new WebSearchResult(
                                    "Title",
                                    "https://example.com/a",
                                    "Snippet",
                                    0.91)));
    WebSearchProperties properties = properties("api-key", 5, SearchDepth.BASIC);
    WebSearchTool tool = new WebSearchTool(new WebSearchProviderRegistry(List.of(provider)), properties, json);
    JsonNode arguments =
            json.readTree(
                    """
                    {"query":"latest java agent","limit":1,"search_depth":"advanced"}
                    """);

    JsonNode output = tool.execute(arguments, context());

    assertThat(provider.lastQuery())
            .isEqualTo(new WebSearchQuery("latest java agent", 1, SearchDepth.ADVANCED));
    assertThat(output.path("provider").asString()).isEqualTo("tavily");
    assertThat(output.path("results").size()).isEqualTo(1);
    assertThat(output.path("references").path(0).path("index").asInt()).isEqualTo(1);
}
```

Add individual tests for missing/blank/over-400 query, non-integer and out-of-range limit, unknown depth, configured defaults, empty result success, limit enforcement, invalid schemes, missing title/snippet normalization, 500-code-point truncation, null score, and Provider failure mapping.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool `
  '-Dtest=WebSearchToolTest,WebSearchPropertiesTest' test
```

Expected: compilation fails because the new web search contracts do not exist.

- [ ] **Step 3: Implement immutable contracts and strict properties**

`SearchDepth.fromValue` accepts exactly lowercase `basic` and `advanced`. `WebSearchProperties` validates:

```java
!connectTimeout.isZero() && !connectTimeout.isNegative()
!requestTimeout.isZero() && !requestTimeout.isNegative()
!toolTimeout.isZero() && !toolTimeout.isNegative()
connectTimeout.compareTo(requestTimeout) <= 0
requestTimeout.compareTo(toolTimeout) < 0
1 <= defaultLimit && defaultLimit <= 20
provider.equals("tavily")
defaultDepth != null
```

Store the API Key as a private record component but override `toString()` to emit `apiKey=***`. Do not include the key length or prefix.

In `WebSearchToolTest`, define a nested `RecordingProvider` implementing `WebSearchProvider`, a fixed `JsonMapper json`, `properties(String apiKey, int limit, SearchDepth depth)`, and `context()` returning an `AgentTool.InvocationContext` with test-only scalar values. The Provider records exactly one `WebSearchQuery` and returns the constructor-supplied immutable result list.

- [ ] **Step 4: Implement `WebSearchTool` minimal behavior**

The Tool must:

1. validate arguments before resolving Provider;
2. use configured values only when call fields are absent;
3. call one Provider;
4. truncate to effective limit;
5. filter non-HTTP(S) URLs;
6. normalize whitespace and truncate snippets by Unicode code points;
7. build `results` and `references` from the same accepted list;
8. map missing configuration to `web_search_not_configured` before Provider I/O;
9. return `toolTimeout` from `timeout()`.

Use stable safe messages without query text or Provider response body.

- [ ] **Step 5: Run focused tests and downstream compilation**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool,agent-web -am test
```

Expected: the old Wikipedia implementation is gone; all focused and downstream tests pass.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-tool backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/builtin/WebSearchTool.java
git commit -m "feat: add provider based web search tool"
```

---

### Task 5: 实现安全 HTTP Transport 与 Tavily Adapter

**Files:**
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchHttpRequest.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchHttpResponse.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/transport/WebSearchTransport.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/transport/JdkWebSearchTransport.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/tavily/TavilyWebSearchProvider.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchTelemetry.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchTelemetryEvent.java`
- Create: `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/Slf4jWebSearchTelemetry.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/web/tavily/TavilyWebSearchProviderTest.java`
- Test: `backend/agent-tool/src/test/java/com/datagent/agentv2/conversation/tool/web/transport/JdkWebSearchTransportTest.java`

**Interfaces:**
- `WebSearchHttpRequest` exposes method, URI, Headers, Body and timeout through accessors; `toString()` redacts Authorization.
- `WebSearchTransport.send(WebSearchHttpRequest)` returns `WebSearchHttpResponse` or throws `InterruptedException`/I/O failure.
- `TavilyWebSearchProvider` implements Provider id `tavily`.
- `WebSearchProvider.search(WebSearchQuery)` declares `WebSearchException, InterruptedException`; cancellation interruption is never converted into a normal empty result.
- `WebSearchException.Kind` contains `AUTHENTICATION`, `RATE_LIMITED`, `TIMEOUT`, `UNAVAILABLE`, `INVALID_RESPONSE`.
- `WebSearchTelemetry.record(WebSearchTelemetryEvent)` accepts `WebSearchTelemetryEvent(String provider, SearchDepth depth, long durationMillis, Integer httpStatus, int resultCount, String resultCode, String requestId)`；the event has no query/body/header/API-Key field，网络尚未取得响应时 `httpStatus` 为 `null`。

- [ ] **Step 1: Write failing Tavily mapping tests with Fake Transport**

Assert the complete outbound contract:

```java
@Test
void sendsFixedTavilyRequestAndParsesResults() throws Exception {
    FakeTransport transport =
            new FakeTransport(
                    response(
                            200,
                            """
                            {
                              "results":[{
                                "title":"Official docs",
                                "url":"https://example.com/docs",
                                "content":"Current documentation",
                                "score":0.84
                              }],
                              "request_id":"request-1"
                            }
                            """));
    TavilyWebSearchProvider provider =
            new TavilyWebSearchProvider(transport, properties("test-secret"), json);

    List<WebSearchResult> results =
            provider.search(new WebSearchQuery("java agent", 3, SearchDepth.BASIC));

    assertThat(transport.request().uri())
            .isEqualTo(URI.create("https://api.tavily.com/search"));
    assertThat(transport.request().headers().get("Authorization"))
            .isEqualTo("Bearer test-secret");
    JsonNode payload = json.readTree(transport.request().body());
    assertThat(payload.path("query").asString()).isEqualTo("java agent");
    assertThat(payload.path("max_results").asInt()).isEqualTo(3);
    assertThat(payload.path("search_depth").asString()).isEqualTo("basic");
    assertThat(payload.path("include_answer").asBoolean()).isFalse();
    assertThat(payload.path("include_raw_content").asBoolean()).isFalse();
    assertThat(payload.path("include_images").asBoolean()).isFalse();
    assertThat(results)
            .containsExactly(
                    new WebSearchResult(
                            "Official docs",
                            "https://example.com/docs",
                            "Current documentation",
                            0.84));
}
```

Add tests for explicit empty `results`, missing `results`, illegal JSON, 401/403, 429, 500, and safe request `toString()`.

Define nested `FakeTransport`, `response(int status, String body)`, and `properties(String apiKey)` helpers inside `TavilyWebSearchProviderTest`. `FakeTransport.send` records the exact request and returns the constructor-supplied response; it performs no network I/O.

Inject a recording `WebSearchTelemetry` into the Provider and assert success captures `provider=tavily`, the requested depth, non-negative duration, status 200, result count 1, result code `success`, and `request-1`. Reflect over `WebSearchTelemetryEvent` record components and assert it has no component named `query`, `body`, `headers`, `apiKey`, or `authorization`.

- [ ] **Step 2: Write failing local Stub Server tests for the JDK Transport**

Use `com.sun.net.httpserver.HttpServer` bound to `InetAddress.getLoopbackAddress()` and an ephemeral port. Assert POST body and Headers arrive, redirect responses are not followed, request timeout throws, default client uses no proxy, and invalid proxy syntax is rejected while constructing properties/transport.

- [ ] **Step 3: Run tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool `
  '-Dtest=TavilyWebSearchProviderTest,JdkWebSearchTransportTest' test
```

Expected: compilation fails because Transport and Adapter are missing.

- [ ] **Step 4: Implement Transport and Adapter**

Build JDK client with:

```java
HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(proxySelector(properties.proxy()))
        .build();
```

An empty proxy uses a `ProxySelector` that always returns `List.of(Proxy.NO_PROXY)`. Explicit proxy syntax must match `http://host:port` or `https://host:port` and produce `ProxySelector.of(new InetSocketAddress(host, port))`.

Use `HttpRequest.BodyPublishers.ofString(body, UTF_8)` and `BodyHandlers.ofString(UTF_8)`. Do not catch `InterruptedException` in Transport; let `WebSearchTool`/coordinator preserve interruption. Never log or return non-2xx Body.

`TavilyWebSearchProvider` maps HTTP status and parsing failures to `WebSearchException.Kind`. `WebSearchTool` maps these kinds respectively to `web_search_authentication_failed`, `web_search_rate_limited`, `tool_timeout`, `web_search_unavailable`, and `web_search_invalid_response`. It rethrows `InterruptedException` unchanged.

Record telemetry in a `finally`-safe path for both success and classified failures. `Slf4jWebSearchTelemetry` logs only event fields; it must never receive the original query, request Body or Headers.

- [ ] **Step 5: Run all `agent-tool` tests for GREEN**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool test
```

Expected: all tests pass without external network access.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-tool
git commit -m "feat: integrate tavily search provider"
```

---

### Task 6: 建立 `agent-skill` Module、严格解析器和 bundled 目录

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-skill/pom.xml`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/SkillDescriptor.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/SkillBundle.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/SkillFrontmatterParser.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/SkillResourceSource.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/ClasspathSkillResourceSource.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/BundledSkillCatalog.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/SkillCatalogStartupValidator.java`
- Create: `backend/agent-skill/src/main/resources/skills/web-research/SKILL.md`
- Test: `backend/agent-skill/src/test/java/com/datagent/agentv2/skill/SkillFrontmatterParserTest.java`
- Test: `backend/agent-skill/src/test/java/com/datagent/agentv2/skill/BundledSkillCatalogTest.java`

**Interfaces:**
- `SkillResourceSource.load()` returns `Map<String, byte[]>` keyed by normalized `skills/<id>/<path>`.
- `BundledSkillCatalog.require(String)`, `bundlesInOrder()`, `summaries()`.
- `SkillBundle.readText(String normalizedPath)` never accesses the filesystem.

- [ ] **Step 1: Write strict parser and resource-boundary tests**

Core parser example:

```java
@Test
void parsesSupportedFrontmatterAndBody() {
    SkillBundle bundle =
            parser.parse(
                    "web-research",
                    Map.of(
                            "SKILL.md",
                            """
                            ---
                            name: web-research
                            description: 当问题需要最新信息或外部公开资料时使用。
                            required_tools:
                              - web_search
                            ---
                            # 联网研究
                            使用搜索结果中的 URL 标注来源。
                            """));

    assertThat(bundle.descriptor().logicalId()).isEqualTo("web-research");
    assertThat(bundle.descriptor().requiredTools()).containsExactly("web_search");
    assertThat(bundle.readText("SKILL.md")).contains("# 联网研究");
    assertThat(bundle.digest()).matches("[0-9a-f]{64}");
}
```

Add one test each for unknown field, missing delimiter, duplicate field, empty description, duplicate dependency, frontmatter name/directory mismatch, invalid UTF-8, absolute path, `..`, backslash traversal, directory target, resource over 256 KiB, duplicate logical ID, and stable digest over sorted normalized resources.

- [ ] **Step 2: Run the new Module tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-skill test
```

Expected: Maven fails because `agent-skill` does not exist.

- [ ] **Step 3: Add Module and implement the minimal strict parser**

Add `<module>agent-skill</module>` immediately after `agent-tool`. Compile dependencies: `agent-tool`, `spring-context`, `spring-core`, `spring-boot-autoconfigure`, `spring-boot-starter-jackson`; test dependency: `spring-boot-starter-test`.

The parser accepts only exact top-level keys `name`, `description`, `required_tools`. Parse the first and second lines containing only `---` as frontmatter boundaries; after `required_tools:` accept only two-space-indented `- <runtime-name>` entries. This intentionally does not implement general YAML.

Digest input is each normalized relative path plus `\0` plus its UTF-8 bytes, sorted lexicographically and fed into one SHA-256 digest.

- [ ] **Step 4: Implement classpath source and startup cross-reference validation**

Use Spring `PathMatchingResourcePatternResolver` pattern `classpath*:skills/*/SKILL.md`, derive the exact logical directory, then load sibling text resources under only `references/`, `assets/`, `templates/`, plus `SKILL.md`. `SkillCatalogStartupValidator` implements `SmartInitializingSingleton`, injects `BundledSkillCatalog` and `ToolSupplyRegistry`, and fails startup when any required Tool is absent.

The validator must not make `BundledSkillCatalog` depend on `ToolSupplyRegistry`; this avoids a cycle when `ToolSupplyRegistry` later collects `SkillReadTool` as an `AgentTool`.

- [ ] **Step 5: Add the concrete `web-research` instructions**

The body must instruct the model to:

- use the Skill for recent or external public facts;
- never put credentials, customer secrets or unredacted personal data in the query;
- treat snippets as summaries, not verified full text;
- cite result URLs in the final answer;
- state failure or no result without inventing sources.

- [ ] **Step 6: Run focused and full Module tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-skill -am test
```

Expected: `agent-tool` and `agent-skill` tests pass.

- [ ] **Step 7: Commit**

```powershell
git add backend/pom.xml backend/agent-skill
git commit -m "feat: add bundled skill catalog"
```

---

### Task 7: 实现 execution Skill 快照与 `skill_read` 原子激活

**Files:**
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/runtime/ExecutionSkillView.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/runtime/ExecutionSkillViews.java`
- Create: `backend/agent-skill/src/main/java/com/datagent/agentv2/skill/tool/SkillReadTool.java`
- Test: `backend/agent-skill/src/test/java/com/datagent/agentv2/skill/runtime/ExecutionSkillViewsTest.java`
- Test: `backend/agent-skill/src/test/java/com/datagent/agentv2/skill/tool/SkillReadToolTest.java`

**Interfaces:**
- `ExecutionSkillViews.open(String)`, `require(String)`, `close(String)`, `contains(String)`.
- `ExecutionSkillView.systemPrompt()`, `read(String logicalId, String path)`.
- `SkillReadTool` runtime name `skill_read`; input `logical_id` required and `path` optional.

- [ ] **Step 1: Write failing snapshot, prompt and activation tests**

```java
@Test
void readingSkillMarkdownActivatesAllDependenciesAndIsIdempotent() throws Exception {
    ExecutionToolViews toolViews = toolViews("skill_read", "web_search");
    toolViews.open("execution-1");
    ExecutionSkillViews skillViews = skillViews(webResearchBundle());
    skillViews.open("execution-1");
    ObjectProvider<ExecutionToolViews> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(toolViews);
    SkillReadTool tool =
            new SkillReadTool(skillViews, provider, JsonMapper.builder().build());
    JsonNode arguments =
            JsonMapper.builder()
                    .build()
                    .readTree("{\"logical_id\":\"web-research\"}");

    JsonNode first = tool.execute(arguments, context("execution-1"));
    JsonNode second = tool.execute(arguments, context("execution-1"));

    assertThat(toolViews.require("execution-1").visibleNames())
            .containsExactly("skill_read", "web_search");
    assertThat(first.path("activated").asBoolean()).isTrue();
    assertThat(second.path("alreadyActivated").asBoolean()).isTrue();
}
```

Add tests for default `SKILL.md`, valid secondary resource without activation, unknown logical ID, absolute/traversal path, digest drift, one dependency outside maximum causing zero activation, and prompt containing summaries but not full Skill body.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-skill `
  '-Dtest=ExecutionSkillViewsTest,SkillReadToolTest' test
```

Expected: compilation fails because runtime views and `SkillReadTool` are absent.

- [ ] **Step 3: Implement immutable snapshots and safe reads**

`open` freezes `(logicalId, description, digest, requiredTools)` in catalog order. `read` fetches the current catalog bundle, compares digest to the frozen digest, validates the normalized path, and returns text. `systemPrompt()` uses this exact structure:

```text
可用技能（仅摘要）：
- web-research：当问题需要最新信息或外部公开资料时使用。
需要使用某项技能时，先调用 skill_read，传入 logical_id；不要根据摘要猜测完整指令。
```

- [ ] **Step 4: Implement `SkillReadTool` without a Spring construction cycle**

Inject `ObjectProvider<ExecutionToolViews>` rather than `ExecutionToolViews` directly. `ToolSupplyRegistry` collects all `AgentTool`, including `SkillReadTool`; lazy retrieval prevents this cycle:

```text
ToolSupplyRegistry -> List<AgentTool> -> SkillReadTool
SkillReadTool -(execute time)-> ExecutionToolViews -> ToolSupplyRegistry
```

For `SKILL.md`, call `activateAll(requiredTools)` only after resource/digest validation. Return JSON fields `logicalId`, `path`, `digest`, `content`, `activatedTools`, `activated`, `alreadyActivated`.

After a successful or rejected activation, emit one structured SLF4J event containing only execution ID, logical ID, digest, activated Tool names and stable result code. Do not log Skill body or user input. Add a Logback test appender assertion that the event contains no `content` field and no body sentence from `SKILL.md`.

- [ ] **Step 5: Run all Skill and Tool tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-skill -am test
```

Expected: all tests pass; `agent-skill` has no dependency on `agent-web`.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-skill
git commit -m "feat: activate tools through skill read"
```

---

### Task 8: 将 `agent-web` ReAct 和 Tool 执行切换到 execution 可见视图

**Files:**
- Modify: `backend/agent-web/pom.xml`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ToolModelDefinitionMapper.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolExecutionCoordinator.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolFailureSanitizer.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ToolFailureCode.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/AgentToolRegistry.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/tool/ToolExecutionCoordinatorTest.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/react/ToolModelDefinitionMapperTest.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/react/ReactExecutionToolVisibilityTest.java`

**Interfaces:**
- `ToolModelDefinitionMapper.toModelDefinitions(List<AgentTool>)` preserves input order.
- `ReactExecutionEngine` obtains definitions from `ExecutionToolViews.require(executionId).visibleTools()` on every loop.
- `ToolExecutionCoordinator` resolves with `requireVisible`, never with global supply.

- [ ] **Step 1: Add `agent-skill` dependency and write failing hidden-call test**

Keep the `agent-tool` dependency introduced in Task 1 and add the `agent-skill` dependency to `agent-web/pom.xml`. Modify `ToolExecutionCoordinatorTest` to open an execution view whose `maximum` contains `echo` but whose `initial` does not. Assert the result is failed with `tool_not_visible`, the Echo Tool execution counter remains zero, and ProcessItem still reaches a failed terminal call to `finishTool`.

Add a sanitizer assertion that an outer `TimeoutException` produces stable code `tool_timeout`; update `ToolFailureCode.TIMEOUT` from the current provisional value `timeout` to the approved specification value `tool_timeout`.

- [ ] **Step 2: Write failing mapper and per-loop definition tests**

Mapper assertion:

```java
@Test
void mapsVisibleToolsInStableOrder() {
    List<ModelToolDefinition> definitions = mapper.toModelDefinitions(List.of(tool("a"), tool("b")));

    assertThat(definitions)
            .extracting(ModelToolDefinition::name)
            .containsExactly("a", "b");
}
```

The ReAct test uses a fake model that records the first and second `tools` argument. Its first response calls `skill_read`; the fake Tool activates `web_search`; its second response is final. Assert the first list omits `web_search` and the second includes it.

- [ ] **Step 3: Run tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ToolExecutionCoordinatorTest,ToolModelDefinitionMapperTest,ReactExecutionToolVisibilityTest' test
```

Expected: hidden Tool currently executes through the global registry, or compilation fails for the new mapper/view constructor.

- [ ] **Step 4: Implement the model definition mapper and execution-scoped resolution**

Map each visible Tool with:

```java
new ModelToolDefinition(
        tool.name(),
        tool.description(),
        json.convertValue(tool.parametersSchema(), Map.class))
```

In `ReactExecutionEngine`, compute `modelTools` inside the `while` loop from the current execution view. In `ToolExecutionCoordinator`, catch `ToolBusinessException` from `requireVisible` and pass it to `ToolFailureSanitizer` before submitting any Future.

Delete `AgentToolRegistry`; no production code may call a global `require(name)` during execution.

- [ ] **Step 5: Run focused and all Web tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am test
```

Expected: all upstream modules and `agent-web` pass.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-web
git commit -m "refactor: enforce execution tool visibility"
```

---

### Task 9: 注入 Skill 摘要并让所有上下文策略保留固定 system message

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextRequestFactory.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/RawConversationContextStrategy.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/context/ConversationSkillPromptTest.java`
- Modify or add assertions: relevant LSM and Hierarchical assembler tests.

**Interfaces:**
- `ConversationContextRequestFactory.create(...)` keeps its public signature.
- It obtains the execution Skill prompt from `ExecutionSkillViews.require(run.executionId())`.
- All context strategies output fixed system messages before persisted conversation history.

- [ ] **Step 1: Write failing Raw strategy preservation test**

```java
@Test
void rawStrategyPrependsFixedSkillSystemMessage() {
    ContextMessage skillPrompt = ContextMessage.system("可用技能（仅摘要）：web-research");
    ConversationContextRequest request = request(List.of(skillPrompt));

    PreparedConversationContext prepared = strategy.prepare(request);

    assertThat(prepared.messages().getFirst()).isEqualTo(skillPrompt);
    assertThat(prepared.messages().get(1).role()).isEqualTo(ContextMessage.Role.USER);
}
```

Add a Factory test asserting the system message contains `web-research` description but does not contain the full body sentence `使用搜索结果中的 URL 标注来源`. Add/retain LSM and Hierarchical assertions that fixed system messages remain first and count toward token estimation.

- [ ] **Step 2: Run focused context tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ConversationSkillPromptTest,*ConversationContextAssemblerTest' test
```

Expected: Raw output omits the fixed system message.

- [ ] **Step 3: Implement prompt injection and Raw preservation**

Factory logic:

```java
String skillPrompt = skillViews.require(run.executionId()).systemPrompt();
List<ContextMessage> fixedSystemMessages =
        skillPrompt.isBlank()
                ? List.of()
                : List.of(ContextMessage.system(skillPrompt));
```

Raw strategy logic:

```java
List<ContextMessage> complete = new ArrayList<>(request.fixedSystemMessages());
complete.addAll(messages.toContextMessages(loader.load(
        request.execution().executionId(),
        request.execution().inputUpToMessageSequence())));
```

Keep fixed system messages out of persisted conversation rows.

- [ ] **Step 4: Run all three context strategy suites**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=*Raw*Test,*Lsm*Test,*Hierarchical*Test,ConversationSkillPromptTest' test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-web backend/agent-context-lsm backend/agent-context-hierarchical
git commit -m "feat: disclose bundled skill summaries"
```

---

### Task 10: 管理 execution 能力视图生命周期并完成运行时装配

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionLauncher.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionCapabilityLifecycleTest.java`
- Modify: `backend/agent-server/src/main/java/com/datagent/agentv2/AgentV2Application.java`
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Add or modify: `backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java`

**Interfaces:**
- `LocalAgentExecutionLauncher` opens Tool and Skill views after transaction commit and before `ReactExecutionEngine.execute`.
- Both views are always closed after engine success or any failure path.
- Configuration properties scan includes `com.datagent.agentv2`.

- [ ] **Step 1: Write failing lifecycle tests for success and failure**

Use real in-memory views and a mocked engine. For success and thrown `RuntimeException`, assert:

```java
assertThat(toolViews.contains("execution-1")).isFalse();
assertThat(skillViews.contains("execution-1")).isFalse();
```

During the mocked engine callback, assert both are true. Add a cancellation/interruption case by making the engine throw `ExecutionStoppedException`.

- [ ] **Step 2: Write failing Spring configuration binding test**

Start an `ApplicationContextRunner` or the existing server context with test properties:

```text
data-agent.tool.web-search.api-key=test-secret
data-agent.tool.web-search.default-limit=7
data-agent.tool.web-search.default-depth=advanced
```

Assert `WebSearchProperties` binds, `ToolSupplyRegistry.names()` contains exactly the four designed Tool names in order, and `BundledSkillCatalog` contains `web-research`.

- [ ] **Step 3: Run tests and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=LocalAgentExecutionCapabilityLifecycleTest,AgentV2ApplicationTest' test
```

Expected: capability views are not opened/closed and new properties are not scanned from the root package.

- [ ] **Step 4: Implement nested cleanup with failure-safe ordering**

Use this structure inside the tenant execution boundary:

```java
toolViews.open(execution.executionId());
try {
    skillViews.open(execution.executionId());
    try {
        executeEngineAndConvergeFailure(execution);
    } finally {
        skillViews.close(execution.executionId());
    }
} finally {
    toolViews.close(execution.executionId());
}
```

This ordering ensures a Skill-open failure still closes the Tool view. Keep existing execution failure convergence unchanged inside `executeEngineAndConvergeFailure`.

- [ ] **Step 5: Expand configuration scanning and add production defaults**

Change:

```java
@ConfigurationPropertiesScan("com.datagent.agentv2")
```

Add the exact `maximum`, `initial`, Provider, API Key, limit, depth, timeout and proxy keys from the specification to `application-business.yml`, with Chinese comments explaining that secrets come from environment variables and `web_search` remains hidden until Skill activation.

- [ ] **Step 6: Run server aggregate tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
```

Expected: all dependent Module tests and server context tests pass.

- [ ] **Step 7: Commit**

```powershell
git add backend/agent-web backend/agent-server
git commit -m "feat: wire execution capability lifecycle"
```

---

### Task 11: 更新说明、执行静态边界检查并完成全量验证

**Files:**
- Modify: `README.md`
- Modify: `docs/tool-skill-capability-design.md`
- Modify: `docs/superpowers/specs/2026-08-30-web-search-tool-skill-modules-design.md`
- Test: all changed Maven modules.

**Interfaces:**
- README documents required `DATA_AGENT_TAVILY_API_KEY`, optional search settings, Skill activation behavior, and no-key failure semantics.
- Existing capability draft is marked partially implemented and points to the approved spec for the delivered slice.

- [ ] **Step 1: Update user-facing documentation**

Replace the README section that describes Chinese Wikipedia with:

```text
内置 Tool 位于独立 agent-tool Module；bundled Skill 位于独立 agent-skill Module。
system_timezone、current_time、skill_read 首轮可见。web_search 默认隐藏，模型读取
web-research 后才在本次 execution 内激活。联网搜索使用 Tavily；未配置
DATA_AGENT_TAVILY_API_KEY 时服务仍可启动，但实际搜索返回 web_search_not_configured。
```

List all environment variables and explicitly state tests never call real Tavily.

After full verification succeeds, update the approved spec status from “设计已批准，实施计划已编写” to “已实现并通过验证”，并 record the fresh verification commands and date without copying build logs into the spec.

- [ ] **Step 2: Check module dependency direction and stale source ownership**

Run:

```powershell
rg -n "<artifactId>agent-web</artifactId>" backend/agent-tool backend/agent-skill
rg -n "org\.apache\.ibatis|com\.baomidou|Mapper" backend/agent-tool/src backend/agent-skill/src
rg --files backend/agent-web/src/main/java | rg "tool/builtin|AgentTool.java|AgentToolRegistry.java"
```

Expected: all three commands return no matches. A no-match `rg` exit code is expected and should be recorded as a successful boundary check, not treated as a build failure.

- [ ] **Step 3: Check for credential leakage patterns**

Run:

```powershell
rg -n "tvly-[A-Za-z0-9_-]+|Bearer tvly-|api-key:\s+[^$]" backend README.md docs
```

Expected: no real-looking credential or literal Bearer credential occurs.

- [ ] **Step 4: Run formatting verification**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B spotless:check
```

Expected: exit code 0.

- [ ] **Step 5: Run focused Module tests fresh**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-tool test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-skill -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
```

Expected: every command exits 0 with zero failures and zero errors.

- [ ] **Step 6: Run full reactor verification fresh**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

Expected: `BUILD SUCCESS`, zero test failures, and no real Tavily network call.

- [ ] **Step 7: Review the diff against all acceptance criteria**

Run:

```powershell
git diff --check
git status --short
git diff --stat HEAD~10..HEAD
```

Confirm explicitly:

- two independent Modules exist and do not depend on `agent-web`;
- first model call cannot see or execute `web_search`;
- `skill_read` atomically activates it for the next model call;
- missing Key is a call-time safe failure;
- empty results and Provider failures remain distinct;
- every execution terminal path clears both views;
- README and design status match the implementation.

- [ ] **Step 8: Commit documentation and final verification metadata**

```powershell
git add README.md docs/tool-skill-capability-design.md `
  docs/superpowers/specs/2026-08-30-web-search-tool-skill-modules-design.md
git commit -m "docs: document web search skill workflow"
```

Do not create a branch, push, merge, or open a PR unless the user separately requests it.
