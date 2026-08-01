# Data Agent V2 后端六模块重构实施计划

> **面向智能体执行者：** 必须使用子技能 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项任务实施本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 在不改变阶段 0/1 行为的前提下，将后端重构为 `agent-contracts`、`agent-platform`、`agent-auth`、`agent-conversation`、`agent-server` 和 `agent-evals` 六个 Maven 模块。

**架构：** 保持单个可部署的模块化 Spring Boot 单体。`agent-auth` 和 `agent-conversation` 只依赖 `agent-platform`，`agent-server` 依赖并装配三个运行时模块；契约测试和评测模块保持独立。

**技术栈：** Java 21、Maven、Spring Boot 4.1.0、MyBatis-Plus 3.5.16、Flyway、PostgreSQL、JUnit 5、Testcontainers。

## 全局约束

- 直接在当前工作区执行，不创建或切换额外 worktree。
- 保留外部未跟踪的 `data-agent-v2/pom.xml`，只使用 `backend/pom.xml` 作为 Maven reactor。
- 不改变 REST/SSE 契约、数据库 Schema、认证/对话行为或双实例部署模型。
- `agent-server` 是唯一声明 Spring Boot 可执行插件和启动类的模块。
- `agent-auth` 与 `agent-conversation` 不得互相依赖。
- 业务组件不得注入 `BaseMapper`；持久化访问继续通过 `ServiceImpl`/`AuditedServiceImpl`。
- 每次提交只暂存本任务涉及的 V2 文件，不提交根目录未跟踪 `pom.xml`。

## 目标结构

```text
backend/
  pom.xml
  agent-contracts/
  agent-platform/
  agent-auth/
  agent-conversation/
  agent-server/
  agent-evals/
```

---

### 任务 1：建立六模块 Maven reactor 和单向依赖

**文件：**
- 修改：`backend/pom.xml`
- 创建：`backend/agent-contracts/pom.xml`
- 创建：`backend/agent-platform/pom.xml`
- 创建：`backend/agent-auth/pom.xml`
- 创建：`backend/agent-conversation/pom.xml`
- 修改：`backend/agent-server/pom.xml`

**接口：**
- 输入：当前 `agent-server` 的依赖集合。
- 输出：六模块 reactor；`agent-auth -> agent-platform`、`agent-conversation -> agent-platform`、`agent-server -> agent-platform + agent-auth + agent-conversation`。

- [ ] **步骤 1：记录重构前验证基线**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

预期：30 项测试通过，reactor 只有父工程、`agent-server` 和 `agent-evals`。

- [ ] **步骤 2：创建模块 POM 并更新 reactor 顺序**

父 POM 的模块顺序必须为：

```xml
<modules>
  <module>agent-contracts</module>
  <module>agent-platform</module>
  <module>agent-auth</module>
  <module>agent-conversation</module>
  <module>agent-server</module>
  <module>agent-evals</module>
</modules>
```

依赖归属：

- `agent-contracts`：`spring-boot-starter-test`、Jackson YAML、networknt JSON Schema Validator，均为测试范围。
- `agent-platform`：JDBC、Flyway、PostgreSQL、MyBatis-Plus Boot 4 Starter、JSqlParser 和 Lombok；测试加入 Spring Boot Test 与 PostgreSQL Testcontainers。
- `agent-auth`：依赖 `agent-platform`，并加入 Web MVC、Validation、Security、Jackson 和 Lombok。
- `agent-conversation`：依赖 `agent-platform`，并加入 Web MVC、Validation、Jackson 和 Lombok。
- `agent-server`：依赖 `agent-platform`、`agent-auth`、`agent-conversation`，并保留 Web MVC、Security、Actuator、Jackson 和 Web MVC Test。

- [ ] **步骤 3：验证 Maven 依赖图可解析**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -DskipTests package
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-auth dependency:tree
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-conversation dependency:tree
```

预期：六个子模块均构建；认证依赖树中没有 `agent-conversation`，对话依赖树中没有 `agent-auth`。

- [ ] **步骤 4：提交模块骨架**

```powershell
git add backend/pom.xml backend/agent-contracts backend/agent-platform backend/agent-auth backend/agent-conversation backend/agent-server/pom.xml
git commit -m "refactor: scaffold backend module boundaries"
```

### 任务 2：将阶段 0 契约测试迁入 agent-contracts

**文件：**
- 移动：`backend/agent-server/src/test/java/com/datagent/agentv2/contracts/*`
- 到：`backend/agent-contracts/src/test/java/com/datagent/agentv2/contracts/*`

**接口：**
- 输入：仓库根 `contracts/` 制品。
- 输出：不依赖任何运行时模块的 17 项契约测试。

- [ ] **步骤 1：机械迁移四个契约测试类**

保留 `ContractFiles` 的向上查找语义和所有 package 名称，不复制测试代码。

- [ ] **步骤 2：运行独立契约模块**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-contracts test
```

预期：`ContractPathsTest`、`ContractExamplesTest` 和 `InventoryConsistencyTest` 共 17 项通过。

- [ ] **步骤 3：证明契约模块没有运行时模块依赖**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-contracts dependency:tree
```

预期：依赖树中不存在 `agent-platform`、`agent-auth`、`agent-conversation` 或 `agent-server`。

- [ ] **步骤 4：提交契约模块迁移**

```powershell
git add backend/agent-contracts backend/agent-server/src/test/java/com/datagent/agentv2/contracts
git commit -m "refactor: isolate contract verification module"
```

### 任务 3：将数据库和 MyBatis-Plus 基础迁入 agent-platform

**文件：**
- 移动：`backend/agent-server/src/main/java/com/datagent/agentv2/platform/config/{ClockConfiguration,MybatisPlusConfiguration}.java`
- 移动：`backend/agent-server/src/main/java/com/datagent/agentv2/platform/context/TenantContext.java`
- 移动：`backend/agent-server/src/main/java/com/datagent/agentv2/platform/id/*`
- 移动：`backend/agent-server/src/main/java/com/datagent/agentv2/platform/persistence/*`
- 移动：`backend/agent-server/src/main/resources/db/migration/*`
- 移动：迁移和 MyBatis-Plus 集成测试到 `backend/agent-platform/src/test/java`
- 创建：`backend/agent-platform/src/test/java/com/datagent/agentv2/platform/AgentPlatformTestApplication.java`
- 修改：`MybatisPlusAuditIntegrationTest.java`

**接口：**
- 输入：现有迁移、审计、租户、逻辑删除和乐观锁实现。
- 输出：可单独测试的共享平台模块，向认证与对话模块提供稳定基础类型。

- [ ] **步骤 1：移动生产代码、迁移和测试**

保持生产 package 不变。将 `Phase1MigrationTest` package 改为 `com.datagent.agentv2.platform.persistence`，使平台测试归属明确。

- [ ] **步骤 2：添加平台测试启动夹具**

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    ClockConfiguration.class,
    MybatisPlusConfiguration.class,
    AuditMetaObjectHandler.class
})
class AgentPlatformTestApplication {}
```

将 `MybatisPlusAuditIntegrationTest` 改为：

```java
@SpringBootTest(classes = AgentPlatformTestApplication.class)
```

保留固定 Clock、专用测试 MapperScan 和 PostgreSQL 动态属性。

- [ ] **步骤 3：运行平台聚焦测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-platform `
  -Dtest='Phase1MigrationTest,MybatisPlusAuditIntegrationTest' test
```

预期：10 项测试通过；迁移执行一次后幂等；审计、租户、逻辑删除和乐观锁行为不变。

- [ ] **步骤 4：确认 server 不再持有平台实现或迁移**

```powershell
rg --files .\backend\agent-server\src | rg 'platform\\(id|persistence)|db\\migration|TenantContext|MybatisPlusConfiguration|ClockConfiguration'
```

预期：无匹配。

- [ ] **步骤 5：提交平台模块迁移**

```powershell
git add backend/agent-platform backend/agent-server
git commit -m "refactor: isolate shared platform module"
```

### 任务 4：精简 agent-server 并固化跨模块边界

**文件：**
- 保留并重包：`AgentV2Application.java`
- 移动/重包：`platform.context.{RequestContext,RequestContextFilter}` 到 `server.context`
- 移动/重包：`platform.web.ApiExceptionHandler` 到 `server.web`
- 修改：对应 Server 测试 import/package。
- 创建：`backend/agent-server/src/test/java/com/datagent/agentv2/server/ModuleBoundaryTest.java`
- 修改：`backend/agent-server/pom.xml`

**接口：**
- 输入：三个运行时模块。
- 输出：唯一可执行 Spring Boot 模块、全局 HTTP 边界和跨模块架构检查。

- [ ] **步骤 1：重包全局 Web 边界并保持现有测试通过**

`RequestContext` 和 `RequestContextFilter` 使用 `com.datagent.agentv2.server.context`；`ApiExceptionHandler` 使用 `com.datagent.agentv2.server.web`。更新测试引用，但不改变请求 ID 或 Problem Detail 行为。

- [ ] **步骤 2：在 server POM 声明唯一可执行插件**

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
```

其他模块不得声明该插件或 `@SpringBootApplication`。

- [ ] **步骤 3：添加 Mapper 注入边界测试**

使用 `ClassPathScanningCandidateComponentProvider` 扫描 `com.datagent.agentv2` 的 Spring 组件。排除 `.persistence.` 包后，断言字段和构造参数类型均不可赋值给 `BaseMapper`。该测试放在 server，因此能看到平台、认证和对话三个运行时模块。

- [ ] **步骤 4：运行 server 聚焦测试与可执行打包**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest='AgentV2ApplicationTest,ApiExceptionHandlerTest,ModuleBoundaryTest' `
  -Dsurefire.failIfNoSpecifiedTests=false test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am package -DskipTests
```

预期：server 测试通过，并生成可执行 `agent-server-0.1.0-SNAPSHOT.jar`。

- [ ] **步骤 5：提交精简服务模块**

```powershell
git add backend/agent-server
git commit -m "refactor: reduce server to runtime assembly"
```

### 任务 5：更新模块文档并完成聚合验证

**文件：**
- 修改：`README.md`
- 修改：`docs/contracts.md`
- 修改：`AGENTS.md`

**接口：**
- 输入：完成后的六模块 reactor。
- 输出：可复现的模块验证命令和后续任务归属规则。

- [ ] **步骤 1：记录模块职责和验证命令**

README 必须列出六个模块和以下命令：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-contracts test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-platform test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

AGENTS 必须明确认证代码进入 `agent-auth`、对话/Redis/Ollama 代码进入 `agent-conversation`、部署装配进入 `agent-server`。

- [ ] **步骤 2：运行格式和完整 reactor 验证**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B spotless:check
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

预期：六模块 reactor 全部成功；现有 30 项测试按模块重新分布后全部通过。

- [ ] **步骤 3：检查依赖和旧路径残留**

```powershell
rg -n 'agent-conversation' .\backend\agent-auth
rg -n 'agent-auth' .\backend\agent-conversation
rg -n '\.\./data-agent|\.\./\.\./data-agent' .\backend
git status --short
```

预期：前三个搜索均无运行时匹配；状态只包含本任务文档修改和外部未跟踪根 `pom.xml`。

- [ ] **步骤 4：提交文档与验证记录**

```powershell
git add README.md AGENTS.md docs/contracts.md
git commit -m "docs: document backend module ownership"
```

- [ ] **步骤 5：分别检查子仓和根仓状态**

```powershell
git status --short
git -C ..\.. status --short --ignore-submodules=none
git log -8 --oneline
```

预期：V2 仅保留外部未跟踪根 `pom.xml`；根仓中原有 `data-agent` 和 `metric-platform` 改动未被修改。
