# 会话侧栏分组实施计划

> **面向执行代理：** 必须使用 `superpowers:subagent-driven-development`（用户选择子代理执行时推荐）或 `superpowers:executing-plans` 逐任务执行。步骤用复选框跟踪。仓库明确禁止未经用户要求创建提交、分支或推送，因此任务完成时记录验证结果，不执行技能模板中的自动提交。

**目标：** 为现有会话侧栏实现持久化的置顶、一级项目分组和未分组列表，支持整理会话且不共享任何信息。

**架构：** 项目单独建表，会话增加可空归属和置顶时间。现有模块内提供项目、组织操作及一致性侧栏聚合接口；前端通过一个侧栏状态 hook 管理刷新和写入，以现有详情 SSE 管理消息和执行。

**技术栈：** Java 21、Spring Boot 4.1.0、MyBatis-Plus 3.5.16、MapStruct 1.6.3、Lombok、Flyway、PostgreSQL；React 19、TypeScript、Lucide、Vitest、Testing Library；后端测试采用现有 JUnit、Mockito、Testcontainers PostgreSQL 17。

**规格：** [已确认设计](../specs/2026-09-15-session-grouping-design.md)。执行每个任务前阅读规格及本计划相应任务。

## 全局约束

- 项目仅用于整理会话，不共享文件、知识、指令、消息或记忆，不影响 Agent 执行及上下文装配。
- 一个会话属于零个或一个项目。归属与置顶是独立属性。
- 删除项目保留会话及置顶状态，项目沿用逻辑删除。
- 可见页面每 60 秒刷新；隐藏时停止周期刷新，重新可见/聚焦立即刷新。
- 不增加摘要 SSE 订阅；不改变详情 SSE 数量及生命周期。
- 项目名去除首尾空白后为 1～60 个 UTF-16 代码单元，允许同名。
- 固定租户与固定身份；不增加认证、成员、权限、嵌套项目、共享上下文或 Maven 模块。
- Mapper/MyBatis API 只在 `agent-persistence`；Bean 使用 Lombok 构造器注入；同构转换使用 Spring MapStruct；输出组装为 View 内的纯工厂。
- 手工编辑使用 `apply_patch`；新增核心类、非直观字段、事务和并发不变量写中文注释；迁移的每张表、每个字段都有中文数据库注释。
- 不提交凭据和生成文件，不重置现有改动，不创建提交、分支或推送。
- 计划中代码是实现关键片段及测试示例；按指定类职责补齐 imports、框架注解和已有接口实现，不引入新抽象或外部依赖。

## 执行基础

所有路径相对仓库根 `E:/IdeaProjects/agent-root/data-agent-v2`。执行前读取本地 AGENTS.md 和 `git status --short`。当前设计文档尚未提交，必须保留。下文 Java 文件统一位于 `com/datagent/agentv2` 包树。

后端聚焦验证统一使用以下模式，以确保父模块参与且不因无同名测试失败：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=ConversationProjectApplicationServiceTest' test
```

修改 `-pl` 和 `-Dtest` 为各任务指定值。每个测试步骤先运行一次确认红灯，再实现并用同一命令确认绿灯。红灯应来自所描述的行为或尚不存在的新符号，不能把 Docker、依赖解析或语法错误当成有效行为失败。Testcontainers 使用 Docker 中的独立 PostgreSQL，不连接用户业务库；Docker 不可用时记录阻塞，继续能运行的单元测试，不能宣称数据库验收通过。

### 文件职责总览

| 任务 | 交付边界 | 主要文件 |
| --- | --- | --- |
| 1 | 表、模型、读写映射和组织局部更新 | 迁移、ConversationProject、ConversationProjectService、ConversationSessionService |
| 2 | 项目与会话组织事务 | ConversationProjectApplicationService、ConversationOrganizationService、ConversationApplicationService |
| 3 | 轻量聚合、REST 和契约 | ConversationSidebarQueryService、三个控制器、View、OpenAPI、SSE 快照 Schema |
| 4 | 真实数据库并发验收 | ConversationOrganizationIntegrationTest |
| 5 | 前端协议及纯分组规则 | backendAgentClient、sidebar.ts、conversationSidebarModel |
| 6 | 读取、写入与生命周期 | useConversationSidebar |
| 7 | 分组 UI 和应用接入 | ConversationSidebar、ConversationProjectRow、ConversationList、App、CSS |
| 8 | 完整回归与使用说明 | README、contracts.md、App 测试和验证记录 |

任务依赖：1 → 2 → 3 → 4；3 → 5 → 6 → 7；4 与 7 通过后执行 8。按顺序执行即可，无需并行改动共享文件。

## 任务 1：持久化项目与会话组织属性

**新增文件：**

- `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationProject.java`
- `backend/agent-persistence/src/main/resources/db/migration/V5__add_conversation_projects.sql`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationProjectEntity.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/mapper/ConversationProjectMapper.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationProjectPersistenceMapper.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationProjectService.java`
- `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/migration/ConversationProjectSchemaMigrationTest.java`
- `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/service/ConversationOrganizationPersistenceTest.java`

**修改文件：**

- `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationSession.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationSessionEntity.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapper.java`
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationSessionService.java`

**接口：**

```java
// ConversationProject：不可变领域 record，工厂和 renamed 维护名称不变量。
public record ConversationProject(String projectId, String name, long version,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ConversationProject create(String projectId, String name);
    public ConversationProject renamed(String name);
}
// ConversationProjectService，暴露领域类型，调用方不访问 Entity/Mapper。
ConversationProject insertProject(ConversationProject project);
ConversationProject lockByProjectId(String projectId); // 不存在或已删除返回 null
List<ConversationProject> listByCreatedAtAsc();
boolean renameProject(String projectId, String name, long expectedVersion);
boolean deleteProject(String projectId, long expectedVersion);
// ConversationSessionService
boolean updateOrganization(String sessionId, String projectId,
        Instant pinnedAt, long expectedVersion);
int detachProject(String projectId);
```

- [x] **1.1 写迁移回归测试。** 参照现有 `ConversationExecutionSchemaMigrationTest` 的独立 PostgreSQL/Flyway 设置。先迁移至 V4，插入 `tenant_local` 和一个旧会话，再迁移全部，验证旧行默认值、外键、索引、注释和删除约束。若 V5 已被其他工作占用，仅顺延新版本，不修改旧迁移。关键查询断言：

```java
try (var connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
     var statement = connection.createStatement();
     var rows = statement.executeQuery("select project_id, pinned_at from conversation_session where conversation_session_id = 'old-session'")) {
    assertThat(rows.next()).isTrue();
    assertThat(rows.getString("project_id")).isNull();
    assertThat(rows.getTimestamp("pinned_at")).isNull();
}
```

- [x] **1.2 运行红灯。** `-pl agent-persistence`、`-Dtest=ConversationProjectSchemaMigrationTest`；预期缺少组织列或项目表。
- [x] **1.3 添加迁移。** 创建表、外键与索引的主体如下，随后为下列全部列使用 `comment on column` 写规格第 5 节对应中文语义，不能只注释新增业务字段。

```sql
create table conversation_project (
    id bigint generated always as identity primary key,
    project_id varchar(64) not null,
    tenant_id varchar(64) not null,
    name varchar(60) not null,
    version bigint not null default 0,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    constraint uq_conversation_project_tenant_id unique (tenant_id, project_id),
    constraint fk_conversation_project_tenant foreign key (tenant_id) references tenant(tenant_id),
    constraint ck_conversation_project_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);
alter table conversation_session add column project_id varchar(64);
alter table conversation_session add column pinned_at timestamptz;
alter table conversation_session add constraint fk_conversation_session_project
    foreign key (tenant_id, project_id) references conversation_project(tenant_id, project_id);
create index idx_conversation_session_project on conversation_session(tenant_id, project_id)
    where is_deleted = false;
comment on table conversation_project is '会话侧栏的一级项目分组，不共享会话内容。';
comment on column conversation_session.project_id is '会话所属项目业务标识；空表示未分组。';
comment on column conversation_session.pinned_at is '会话置顶时间；空表示未置顶，重复置顶不刷新。';
```

- [x] **1.4 写真实更新测试并确认红灯。** 使用 `AgentPersistenceTestApplication`，显式导入两个 Service 与 Spring MapStruct 生成实现；沿用现有持久化测试的固定租户调用。用 JdbcTemplate 建立项目及会话测试行，服务调用后直接查询数据库：

```java
TenantContext.run("tenant_local", () -> {
    assertThat(sessions.updateOrganization("session-1", "project-1",
            Instant.parse("2026-09-15T01:00:00Z"), 0)).isTrue();
    assertThat(sessions.updateOrganization("session-1", null, null, 1)).isTrue();
    assertThat(sessions.updateOrganization("session-1", "project-1", null, 0)).isFalse();
});
assertThat(jdbc.queryForMap("select project_id, pinned_at, version from conversation_session where conversation_session_id='session-1'"))
        .containsEntry("project_id", null).containsEntry("pinned_at", null).containsEntry("version", 2L);
```

- [x] **1.5 实现模型、Entity 和 Mapper。** 项目 record 的名称使用 `trim()`，空值、空白和长度超过 60 抛 `IllegalArgumentException`；工厂版本为 0，审计时间由持久化保存返回。Entity 继承 `TenantAuditedEntity`，使用 `@Getter @Setter @NoArgsConstructor(access = PROTECTED)`，版本字段加 `@Version`。普通 Mapper 继承 BaseMapper；转换 Mapper 为 Spring component model。

```java
private static String normalizedName(String name) {
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty() || normalized.length() > 60) {
        throw new IllegalArgumentException("Project name must contain 1-60 characters");
    }
    return normalized;
}
// 已有会话 updateEntity 方法追加这两个明确忽略项；toEntity/toModel 保留字段映射。
@Mapping(target = "projectId", ignore = true)
@Mapping(target = "pinnedAt", ignore = true)
```

- [x] **1.6 实现组织局部更新及项目服务。** `updateOrganization` 使用版本条件和显式 set(null)；使用与现有服务相同的空 Entity 参数触发审计填充，不同时让 @Version 自动加一与 Wrapper 加一重复生效。

```java
return baseMapper.update(new ConversationSessionEntity(),
        Wrappers.lambdaUpdate(ConversationSessionEntity.class)
            .eq(ConversationSessionEntity::getConversationSessionId, sessionId)
            .eq(ConversationSessionEntity::getVersion, expectedVersion)
            .set(ConversationSessionEntity::getProjectId, projectId)
            .set(ConversationSessionEntity::getPinnedAt, pinnedAt)
            .setIncrBy(ConversationSessionEntity::getVersion, 1)) == 1;
```

`detachProject` 采用同样更新方式，条件仅为当前项目，清空归属、版本加一，不设置 pinnedAt。项目查询按创建时间及项目 ID 正序；锁查询使用已有 `.last("FOR UPDATE")` 模式；重命名用 name/version 局部更新；删除用带版本条件的 `remove`，复用逻辑删除注解。服务不接收 HTTP 请求类型。

- [x] **1.7 运行绿灯并审查映射。** 运行 `ConversationProjectSchemaMigrationTest,ConversationOrganizationPersistenceTest`；再验证用旧领域对象执行普通会话更新不恢复旧组织值、读取映射带回新字段、批量解除归属保留置顶且增加版本。记录结果，不提交。

## 任务 2：项目及组织操作事务

**新增文件：**

- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/project/ConversationProjectApplicationService.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/project/ConversationOrganizationService.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/project/ConversationProjectApplicationServiceTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/project/ConversationOrganizationServiceTest.java`

**修改文件：** `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationApplicationService.java` 及其已有 `ConversationApplicationServiceTest.java`。

**输入接口：** 任务 1 的项目及会话持久化服务。

**输出接口：**

```java
// ConversationProjectApplicationService
ConversationProject create(String name);
ConversationProject rename(String projectId, String name, long expectedVersion);
void delete(String projectId, long expectedVersion);
// ConversationOrganizationService
ConversationSession setOrganization(String sessionId, String projectId,
        boolean pinned, long expectedVersion);
// ConversationApplicationService：保留旧 Java 调用并将其转发到新增重载。
ConversationView create(String title, String tag, String projectId);
```

- [x] **2.1 写组织行为测试。** 单元测试注入 Mockito 的两个持久化服务，针对保持原置顶时间、取消置顶、无变化、旧版本、缺失项目/会话分别断言。核心锁顺序测试：

```java
var projects = mock(ConversationProjectService.class);
var sessions = mock(ConversationSessionService.class);
var session = ConversationSession.create("session-1", "test", null, Instant.now());
session.setProjectId("project-1");
session.setPinnedAt(Instant.parse("2026-09-15T01:00:00Z"));
when(projects.lockByProjectId("project-2"))
        .thenReturn(ConversationProject.create("project-2", "分析"));
when(sessions.lockById("session-1")).thenReturn(session);
when(sessions.updateOrganization("session-1", "project-2", session.getPinnedAt(), 0))
        .thenReturn(true);
when(sessions.findByUuid("session-1")).thenReturn(session);
new ConversationOrganizationService(projects, sessions)
        .setOrganization("session-1", "project-2", true, 0);
var order = inOrder(projects, sessions);
order.verify(projects).lockByProjectId("project-2");
order.verify(sessions).lockById("session-1");
order.verify(sessions).updateOrganization("session-1", "project-2", session.getPinnedAt(), 0);
```

- [x] **2.2 运行红灯。** `-pl agent-web`、`-Dtest=ConversationOrganizationServiceTest,ConversationProjectApplicationServiceTest`。
- [x] **2.3 实现事务核心。** 服务使用公开 `@Transactional` 方法及 Lombok 构造器；异常沿用 `ResponseStatusException`。名称非法捕获领域异常并转为 400；缺失为 404；版本不匹配或更新数非 1 为 409。组织方法先校验 ID/版本，再锁目标项目及会话。目标项目非空时必须非空白且最多 64 字符。

```java
// 位于完成目标项目、会话存在性和 expectedVersion 校验之后。
Instant targetPinnedAt = pinned
        ? (session.getPinnedAt() == null ? Instant.now() : session.getPinnedAt())
        : null;
if (Objects.equals(session.getProjectId(), projectId)
        && Objects.equals(session.getPinnedAt(), targetPinnedAt)) {
    return session;
}
if (!sessions.updateOrganization(sessionId, projectId, targetPinnedAt, expectedVersion)) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation session version is stale");
}
return sessions.findByUuid(sessionId);
```

项目创建使用 `ids.next("project")`；重命名先锁项目和验版本，再调用领域 `renamed` 验名称并局部更新，返回最新锁查询结果；删除先锁项目验版本，调用 `detachProject`，再 `deleteProject`，失败抛异常回滚。不得先清空会话再校验项目版本。

- [x] **2.4 增加项目内新建与无 I/O 回归。** 新 create 重载先锁目标活动项目（null 跳过），创建领域会话并设置归属后保存，仍用 `composeFromNewSession`。两个重载均声明事务，避免旧重载自调用导致入口无事务。保留旧 `create(title, tag)` 的 Java 兼容。修改原构造器测试注入新增项目服务。

```java
ConversationView created = service.create("新会话", null, null);
assertThat(created.messages()).isEmpty();
verifyNoInteractions(messages, executions, events, checkpoints, submissions, artifacts, projects);
```

该片段使用原 `ConversationApplicationServiceTest` 已有局部 mocks，并增加 projects mock；同文件另测 projectId 非空时只增加项目锁，不查询消息或文件。

- [x] **2.5 运行绿灯。** 三个服务测试同时通过；确认 no-op 不调用 update、null 目标不调用项目锁、删除失败抛异常，真正回滚留给任务 4 的数据库验证。记录结果。

## 任务 3：侧栏聚合、REST 与契约

**新增文件：**

- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/project/ConversationProjectView.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/project/ConversationSidebarView.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/project/ConversationSidebarQueryService.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationProjectController.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationSidebarController.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/project/ConversationSidebarQueryServiceTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/controller/ConversationOrganizationControllerTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/controller/ConversationProjectControllerTest.java`
- `contracts/examples/rest/conversation-sidebar.yaml`

**修改文件：**

- `backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationController.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationSummaryView.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationView.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/session/ConversationViewCompositionTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractPathsTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractExamplesTest.java`
- `contracts/openapi.yaml`、`contracts/schemas/session-sse-event.schema.json`
- `contracts/examples/rest/session-create.yaml`、`contracts/examples/rest/session-running.yaml`、`contracts/examples/sse/session-snapshot.json`

**接口：**

```java
record ConversationProjectView(String projectId, String name, long version,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    static ConversationProjectView composeFromProject(ConversationProject project);
}
record ConversationSidebarView(List<ConversationProjectView> projects,
        List<ConversationSummaryView> sessions) {
    static ConversationSidebarView composeFromProjectsAndSessions(
            List<ConversationProject> projects, List<ConversationSummaryView> sessions);
}
// ConversationSidebarQueryService，依赖项目持久化服务和 ConversationApplicationService。
ConversationSidebarView getSidebar();
```

- [x] **3.1 写响应边界红灯测试。** standalone MockMvc 参考 `ArtifactControllerTest`。有效组织请求必须调用服务；分别省略三个键、传 null pinned/version、负版本、空 ID 均返回 400；显式 null projectId 接受。关键例子使用 mock 的 ConversationOrganizationService、ConversationApplicationService 及 ConversationController 既有依赖构建 mvc：

```java
mvc.perform(put("/api/v1/agent-sessions/session-1/organization")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"pinned\":false,\"expectedVersion\":0}"))
    .andExpect(status().isBadRequest());
verifyNoInteractions(organizations);
```

项目控制器测试同时覆盖创建 Location/201、重命名/200、删除/204、必传版本与应用层 404/409。运行 `-pl agent-web`、`-Dtest=ConversationOrganizationControllerTest,ConversationProjectControllerTest,ConversationSidebarQueryServiceTest` 确认红灯。

- [x] **3.2 增加投影及聚合。** 两个字段加入摘要和详情 record 的所有工厂；聚合 View 固定返回非 null 数组，不包含消息。query 服务从外部代理调用，使用以下事务边界：

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public ConversationSidebarView getSidebar() {
    return ConversationSidebarView.composeFromProjectsAndSessions(
            projects.listByCreatedAtAsc(), conversations.listSummaries());
}
```

单元测试验证两个服务各调用一次，空项目保留；已有 View 测试新增 null 默认值和指定 projectId/pinnedAt 的两个场景。

- [x] **3.3 实现请求和控制器。** 组织输入使用可检测缺失字段的 record：

```java
record SetOrganizationRequest(
        @JsonProperty(required = true) @Size(min = 1, max = 64) String projectId,
        @NotNull Boolean pinned,
        @NotNull @Min(0) Long expectedVersion) {}
```

使用现有 `com.fasterxml.jackson.annotation.JsonProperty` 与 Jakarta Validation，实际 Jackson 3 路径由 MockMvc 测试确认缺失 projectId 与显式 null 可区分，不全局调整 ObjectMapper。create 请求的 projectId 可省略，使用相同非空字符串长度约束。组织服务返回会话后，通过本任务新增的 `ConversationApplicationService.summary(String sessionId): ConversationSummaryView` 组装轻量摘要：内部使用现有 `findByUuid` 与 `findByConversationSessionIdsOrderByCreatedAtDesc(List.of(sessionId))`，取首条执行或 null，调用 `ConversationSummaryView.composeFromSession`；不存在会话返回 404，禁止调用完整 `get`。在 `ConversationApplicationServiceTest` 增加该方法不查询 messages/events/checkpoints/artifacts 的断言。

- [x] **3.4 修改 OpenAPI 和快照 Schema。** 增加五个操作，保持已有九个，`ContractPathsTest` 的操作总数从 9 改为 14，新增四个路径：sidebar、projects、projects/{projectId}、sessions/{conversationSessionId}/organization。请求 required 列表必须包含全部三个组织键：

```yaml
SetConversationOrganizationRequest:
  type: object
  additionalProperties: false
  required: [projectId, pinned, expectedVersion]
  properties:
    projectId: { type: [string, 'null'], minLength: 1, maxLength: 64 }
    pinned: { type: boolean }
    expectedVersion: { type: integer, minimum: 0 }
```

记录全部状态码，给项目内创建增加 404。会话快照的 `session` 当前只声明 object，增加 `projectId` 与 `pinnedAt` 的属性类型及 required，保留其他既有字段的兼容；所有受影响示例补显式 null。不要把 REST `ConversationSessionSummary` 与摘要 SSE 的 `ConversationSummaryStreamItem` 混为一谈，也不改 `conversation-summary-snapshot.json` 协议。

- [x] **3.5 运行绿灯与契约验证。** `-pl agent-web`，`-Dtest=ConversationOrganizationControllerTest,ConversationProjectControllerTest,ConversationSidebarQueryServiceTest,ConversationViewCompositionTest,ContractPathsTest,ContractExamplesTest`。验证新增聚合示例符合实际响应，领域字段不会缺失，摘要返回不含正文。

## 任务 4：事务与并发的真实数据库验收

**新增文件：** `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ConversationOrganizationIntegrationTest.java`。

**输入：** 任务 1～3 的实际服务与 Spring 代理，不用 mock 证明回滚。

- [x] **4.1 建立 Testcontainers 集成测试。** 参考 `AgentExecutionSubmissionIntegrationTest`/`ConversationSummarySseIntegrationTest` 的应用启动和动态数据源，注入项目、组织和会话服务、JdbcTemplate、TransactionTemplate。所有 fixture 仅写独立测试容器，不调用真实模型。

```java
var project = projects.create("分析");
var session = conversations.create("保留此会话", null, project.projectId());
organizations.setOrganization(session.conversationSessionId(), project.projectId(), true, session.version());
projects.delete(project.projectId(), project.version());
var remaining = conversations.get(session.conversationSessionId());
assertThat(remaining.projectId()).isNull();
assertThat(remaining.pinnedAt()).isNotNull();
assertThat(remaining.title()).isEqualTo("保留此会话");
```

- [x] **4.2 在修复前运行并发断言。** 用 CountDownLatch 和独立事务控制锁持有，禁止依赖 Thread.sleep。一个事务先持项目锁并调用删除，另一事务在删除提交前尝试组织移入，删除释放后断言移入为 404，数据库没有活动会话引用已删除项目。另一用例交换先后，先提交移入，再删除，最终会话归属为空。

```java
var acquired = new CountDownLatch(1);
var release = new CountDownLatch(1);
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<?> deleting = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
        projectStore.lockByProjectId(project.projectId());
        projects.delete(project.projectId(), project.version());
        acquired.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }));
    assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
    Future<?> moving = executor.submit(() -> organizations.setOrganization(
            session.conversationSessionId(), project.projectId(), false, session.version()));
    try {
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%conversation_project%')", Boolean.class)));
    } finally {
        release.countDown();
    }
    deleting.get(5, TimeUnit.SECONDS);
    assertThatThrownBy(() -> moving.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ResponseStatusException.class);
}
```

进一步从异常 cause 校验 HttpStatus 404，避免把任意异常当正确结果。数据库锁等待断言确保移入确实在删除提交前到达，不能仅依靠启动线程推断竞争已经发生。补充同样顺序下项目内创建，使用外层事务在项目删除后主动抛 RuntimeException 验证整个解除归属及删除回滚。

- [x] **4.3 验证运行与锁边界。** 用现有提交集成测试中的固定执行 fixture 验证删除前后执行仍 running、消息序号及记录数不变；并发普通标题更新不会恢复归属。测试 REPEATABLE_READ：外层读事务先读取侧栏，另一事务提交删除，原读事务再次读取仍见同一事务视图，新事务读取见已删除结果。
- [x] **4.4 运行聚焦绿灯。** `-pl agent-server`、`-Dtest=ConversationOrganizationIntegrationTest`。若失败，修改任务 1～3 对应局部实现，不新增事件总线或分布式锁。保存并发用例结果。

## 任务 5：前端协议与纯分组规则

**新增文件：**

- `frontend/src/domain/sidebar.ts`
- `frontend/src/app/conversationSidebarModel.ts`
- `frontend/src/app/conversationSidebarModel.test.ts`

**修改文件：** `frontend/src/domain/agent.ts`、`frontend/src/api/backendAgentClient.ts`、`frontend/src/api/backendAgentClient.test.ts`、`frontend/src/App.test.tsx` 中客户端 mock 的新增方法和 fixture 字段。

**接口：**

```ts
export interface ConversationProject {
  projectId: string; name: string; version: number; createdAt: string; updatedAt: string;
}
export interface ConversationSidebarSnapshot {
  projects: ConversationProject[]; sessions: ConversationSession[];
}
export interface SetConversationOrganization {
  projectId: string | null; pinned: boolean; expectedVersion: number;
}
// ConversationSession 增加必传 nullable 字段：projectId、pinnedAt。
// AgentBackendClient 增加以下方法，createSession 第三个参数可选以兼容原调用。
getSidebar(signal?: AbortSignal): Promise<ConversationSidebarSnapshot>;
createProject(name: string): Promise<ConversationProject>;
renameProject(projectId: string, name: string, expectedVersion: number): Promise<ConversationProject>;
deleteProject(projectId: string, expectedVersion: number): Promise<void>;
setOrganization(sessionId: string, request: SetConversationOrganization): Promise<ConversationSession>;
createSession(title: string, tag: string, projectId?: string | null): Promise<ConversationSession>;
getSidebarStorageKey(): string;
// conversationSidebarModel.ts
export interface SidebarGroups {
  pinned: ConversationSession[];
  projects: { project: ConversationProject; sessions: ConversationSession[] }[];
  ungrouped: ConversationSession[];
}
export function groupSidebar(snapshot: ConversationSidebarSnapshot, query: string): SidebarGroups;
```

- [x] **5.1 写分组红灯测试。** 所有示例字段显式填全。断言置顶不重复、取消置顶归位、项目顺序不受会话更新时间影响、同时间 ID 稳定排序、搜索 tag 生效、空项目保留：

```ts
const s: ConversationSession = {
  conversationSessionId: 's1', title: '分析', tag: 'debug',
  createdAt: '2026-09-15T01:00:00', updatedAt: '2026-09-15T01:00:00',
  version: 0, projectId: 'p1', pinnedAt: '2026-09-15T01:00:00Z',
  messages: [], execution: null,
};
const p: ConversationProject = {
  projectId: 'p1', name: '项目', version: 0,
  createdAt: '2026-09-15T01:00:00', updatedAt: '2026-09-15T01:00:00',
};
expect(groupSidebar({ projects: [p], sessions: [s] }, 'DEBUG'))
  .toEqual({ pinned: [s], projects: [], ungrouped: [] });
expect(groupSidebar({ projects: [p], sessions: [{ ...s, pinnedAt: null }] }, '')
  .projects[0].sessions).toHaveLength(1);
```

搜索时仅保留含未置顶命中会话的项目；置顶命中仍以来源显示项目名，查完整 snapshot 中的项目名称。空查询保留全部空项目。

- [x] **5.2 运行红灯。** `npm.cmd --prefix frontend test -- src/app/conversationSidebarModel.test.ts src/api/backendAgentClient.test.ts`。
- [x] **5.3 实现客户端。** 沿用 fetchImpl、BackendApiError 和 JSON 适配，增加 sibling endpoints，基于当前 sessions baseUrl 最后一个路径段推导同级 `agent-projects`/`agent-sidebar`，保留反向代理前缀及绝对 URL。不硬编码所有新请求到根 `/api/v1`。

```ts
const normalizedSessionBase = baseUrl.replace(/\/+$/, '');
const apiBase = normalizedSessionBase.slice(0, normalizedSessionBase.lastIndexOf('/'));
const projectsBase = `${apiBase}/agent-projects`;
const sidebarBase = `${apiBase}/agent-sidebar`;
// 以 sidebarBase 形成折叠存储键；同一代理地址切换后端不保证隔离。
const storageKey = `data-agent:sidebar:${sidebarBase}:collapsed`;
```

所有项目 ID/会话 ID 使用 encodeURIComponent；删除 204 不调用 response.json；getSidebar 传 signal；新写入不使用现有仅针对幂等消息的自动网络重试。客户端适配器将缺省组织字段归一化为 null，实际本期服务响应必须明确包含。测试默认地址、带代理前缀的地址、绝对地址、null body、409、204 和取消读取。

- [x] **5.4 实现纯派生。** 先按标题+tag 过滤，再分置顶、项目及未分组；通过 Map 按项目 ID 汇集一次，避免逐项目重复扫描。pinnedAt 按时间值比较，其他时间沿用项目现有比较语义，再以业务 ID 打破平局。不修改输入数组。

```ts
const needle = query.trim().toLowerCase();
const visible = snapshot.sessions.filter(s => `${s.title} ${s.tag}`.toLowerCase().includes(needle));
const pinned = visible.filter(s => s.pinnedAt !== null).sort((a, b) =>
  Date.parse(b.pinnedAt!) - Date.parse(a.pinnedAt!) || b.conversationSessionId.localeCompare(a.conversationSessionId));
```

- [x] **5.5 运行绿灯。** 同命令通过，更新所有受类型新增影响的会话 fixture 和 AgentBackendClient mocks，不删除已有断言。

## 任务 6：侧栏请求生命周期

**新增文件：** `frontend/src/app/useConversationSidebar.ts`、`frontend/src/app/useConversationSidebar.test.tsx`。

**输入：** AgentBackendClient、任务 5 的 Snapshot。

**接口：**

```ts
export interface SidebarMutationResult {
  outcome: 'saved' | 'rejected' | 'unknown'; refreshed: boolean;
}
export function useConversationSidebar(client: AgentBackendClient): {
  snapshot: ConversationSidebarSnapshot;
  loading: boolean; busy: boolean; error: string | null;
  refresh: () => Promise<boolean>;
  mutate: (operation: () => Promise<unknown>) => Promise<SidebarMutationResult>;
};
```

明确 saved+refreshed=false 与 rejected 不同，避免 UI 将已成功的创建再次提交。折叠状态不由聚合刷新重置，由任务 7 维护。

- [x] **6.1 写旧响应晚到测试。** 用 renderHook 和可控 Promise，不依赖网络延迟：

```ts
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const old = deferred<ConversationSidebarSnapshot>();
const fresh = { projects: [], sessions: [] } satisfies ConversationSidebarSnapshot;
// backend 使用本任务本地工厂：任务 5 的接口逐方法 vi.fn，getSidebar 按如下顺序返回。
backend.getSidebar = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce(fresh);
const { result } = renderHook(() => useConversationSidebar(backend));
await act(async () => { await result.current.mutate(async () => undefined); });
await act(async () => { old.resolve({ projects: [project], sessions: [] }); });
expect(result.current.snapshot.projects).toEqual([]);
```

该测试 project 使用任务 5 相同字段结构在本文件直接定义。补充写入期间周期/聚焦触发不发新读取、保存成功后刷新失败返回 saved、400/409 为 rejected、网络异常为 unknown。运行 `npm.cmd --prefix frontend test -- src/app/useConversationSidebar.test.tsx` 确认红灯。

- [x] **6.2 实现代次读取。** ref 保存 generation、AbortController、mounted、inFlight、writing，React state 保存快照、加载/繁忙/错误。每次真正开始读取时：

```ts
const ticket = ++generation.current;
controller.current?.abort();
const nextController = new AbortController();
controller.current = nextController;
try {
  const next = await client.getSidebar(nextController.signal);
  if (!mounted.current || ticket !== generation.current) return false;
  setSnapshot(next);
  setError(null);
  return true;
} catch (failure) {
  if (!mounted.current || ticket !== generation.current) return false;
  setError('侧栏同步失败，点击重试。');
  return false;
}
```

普通相邻触发共享同一个 inFlight Promise；写入开始强制 generation++ 并 abort，使旧读取不能清除新错误或 busy。返回后仅当前代次可以结束 loading；卸载和 client 变化时清理所有 ref 的归属。

- [x] **6.3 实现串行变更。** 同步 ref 检查 writing，避免 state 渲染前重复点击穿透。设置 writing 与 busy、使旧读取失效，然后调用 operation。成功、HTTP 错误、网络错误分别记录 outcome；finally 路径必须执行新的权威读取，读取期间保持 busy。内部刷新允许越过 writing 限制，公开 refresh 在 writing 时只登记待刷新。刷新失败保留快照，按 outcome 呈现规格第 9.4 节文案；最后恢复写入口，不抛出会导致页面未处理拒绝的错误。

- [x] **6.4 实现计时器与清理。** 单个 setInterval 60_000，visible 才运行；focus/visibilitychange 合并在途刷新；隐藏时清理 interval，再次显示立即刷新并创建新 interval。首次挂载仅有一次读取；StrictMode 重挂载或更换客户端后旧回调不能写新状态。

```ts
const onVisibility = () => {
  if (document.visibilityState === 'hidden') {
    window.clearInterval(timer);
    timer = undefined;
  } else {
    void refresh();
    if (timer === undefined) timer = window.setInterval(() => void refresh(), 60_000);
  }
};
```

timer 为 effect 局部 `number | undefined`；effect cleanup 移除事件、清 interval、abort，客户端变化重新建立。

- [x] **6.5 运行绿灯。** 假时钟验证一分钟刷新、不要求 running、隐藏不轮询、显示刷新、相邻触发合并、卸载清理。另测新的 active 消息/SSE 不依赖 hook busy，hook 本身不得请求会话详情。

## 任务 7：分组 UI 与 App 接入

**新增文件：**

- `frontend/src/components/ConversationSidebar.tsx`
- `frontend/src/components/ConversationProjectRow.tsx`
- `frontend/src/components/ConversationSidebar.test.tsx`

**修改文件：** `frontend/src/components/ConversationList.tsx`、`frontend/src/App.tsx`、`frontend/src/App.test.tsx`、`frontend/src/styles.css`。

**接口：** ConversationSidebar 输入 snapshot、activeSessionId、loading/busy/error、storageKey，以及 onOpen、onRename、onCreateSession(projectId: string|null)、onCreateProject(name)、onRenameProject(projectId,name,version)、onDeleteProject(projectId,version)、onSetOrganization(sessionId,request)、onRefresh。所有写回调返回 `Promise<SidebarMutationResult>`，会话原标题回调保持 `Promise<boolean>` 供现有列表复用。ConversationProjectRow 为这些回调的单项目适配，无独立后端调用。

- [x] **7.1 写 UI 红灯。** 为三个区使用可查询的 `<section aria-label>`，菜单使用原生按钮和 select，删除用带 dialog 语义的确认层。用完整 mock props 构建测试，断言：

```ts
expect(within(screen.getByRole('region', { name: '置顶' })).getByText('每周经营数据复盘'))
  .toBeInTheDocument();
expect(screen.getAllByText('每周经营数据复盘')).toHaveLength(1);
fireEvent.click(screen.getByRole('button', { name: '每周经营数据复盘的更多操作' }));
fireEvent.click(screen.getByRole('button', { name: '取消置顶' }));
expect(onSetOrganization).toHaveBeenCalledWith('s1', {
  projectId: 'p1', pinned: false, expectedVersion: 4,
});
```

fixture 明确 s1 的 projectId=p1、version=4、pinnedAt 非空；另测全局新建传 null、项目内传 ID、移动保持置顶、项目删除不会调用删除会话函数。运行 `npm.cmd --prefix frontend test -- src/components/ConversationSidebar.test.tsx` 确认红灯。

- [x] **7.2 实现分区与会话菜单。** ConversationList 保留既有改名逻辑，将外层改为 row 容器，打开按钮与菜单触发按钮为兄弟；新组织回调通过 props 传入，不直接请求 API。置顶来源从完整项目 Map 查找。样式使用已有 class 模式与 Lucide，不复制截图中的无关菜单。

```tsx
<div className="session-row">
  <button type="button" className="session-link" onClick={() => onOpen(session.conversationSessionId)}>
    <span className="session-link-title">{session.title}</span>
  </button>
  <button type="button" aria-label={`${session.title}的更多操作`}
    aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>…</button>
</div>
```

此片段仅展示结构；实际保留当前 active、onDoubleClick 和状态标识。菜单被 Escape 关闭时 `triggerRef.current?.focus()`；菜单选项不得冒泡打开会话。超过一行标题用 text-overflow，移动菜单 projectId='' 转成 null。

- [x] **7.3 实现项目与折叠。** 项目行独立触发折叠，新增项目展开；搜索期间用 `expanded = hasSearch || !collapsedIds.has(projectId)`，不写入 collapsedIds。用 try/catch 读取/写入 localStorage，解析为字符串数组并过滤当前存在项目；用户移动成功后展开目标，维持搜索词。创建和改名保存 trim 后名称，中文 composition 期间忽略 Enter。

- [x] **7.4 接入 App 的数据来源。** 用 hook 代替 App 原初次 listSessions 及一分钟后台摘要 effect，删除仅因此不用的 hasBackgroundProcessingSession 和相关常量。侧栏项目/组织来自 snapshot，App 的 sessions 继续缓存详情；摘要合并显式保留 messages、execution、workflow。开会话详情、提交/改名响应和详情 SSE 不写侧栏组织状态；本地 create/rename 完成后刷新 sidebar，创建同时保持原导航流程。若已创建会话但刷新失败，结果为 saved，关闭创建输入，提示刷新失败，禁止再次创建同一个目标。

```ts
// 当前 App mergeSessionSummaries 的 detail 分支必须保留这三项。
return { ...detail, ...summary,
  messages: detail.messages, execution: detail.execution, workflow: detail.workflow };
```

详情继续更新当前消息和运行状态；侧栏当前会话状态可从详情最新状态覆盖用于展示，但归属和置顶只取 snapshot。不要从 getSession 得到的整对象替换项目列表。全局/项目内创建使用同一 hook.mutate 防止并发创建和项目删除。

- [x] **7.5 加入异步 App 回归。** 在 `App.test.tsx` 复用原 client factory：getSidebar 返回包含旧 list fixture 的聚合对象；保留 listSessions 兼容方法但断言 App 不再周期调用它。延迟旧 getSession，先让聚合显示新 projectId，再解析旧详情，断言会话仍在新项目。另测刷新保留 Workflow 及消息，sidebar busy 时提交消息仍可调用，项目折叠不改变 route。
- [x] **7.6 运行绿灯和构建。** `npm.cmd --prefix frontend test -- src/components/ConversationSidebar.test.tsx src/App.test.tsx src/app/useConversationSidebar.test.tsx`；再执行 `npm.cmd --prefix frontend run build`。仅格式化本次改动文件，不能为通过 format:check 重排不相关文件。

## 任务 8：全链路验收与文档

**修改文件：** `README.md`、`docs/contracts.md`、本实施计划的验证记录；必要时补任务 4/7 的对应测试。

- [x] **8.1 执行聚焦验收。** 再跑所有新增后端和前端测试，先修实际失败，不扩大功能。对照已确认规格逐项勾选：唯一展示、归属与置顶正交、空项目、搜索 tag、删除保留运行、错误保留数据、焦点刷新、旧响应隔离。
- [x] **8.2 执行后端回归。** 任务 1～4 均通过后运行以下命令，不跳过失败或容器依赖：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
```

检查 `ModuleBoundaryTest`、旧会话创建/提交、标题修改、上下文及产物测试结果；这些边界失败不能通过扩大实体依赖或跳过测试解决。

- [x] **8.3 执行前端完整验证。**

```powershell
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
git diff --check
```

浏览器联调使用已获授权的本地开发环境：在两个标签页打开同一会话，验证一端创建项目/移入/置顶，另一端重新聚焦收敛；320px 和桌面宽度确认操作不重叠；键盘打开菜单、Esc 恢复焦点、中文改名、删除项目后继续对话。未能启动联调时明确记录未验证项，不把 jsdom 等同于视觉验收。

- [x] **8.4 更新中文说明。** README 的本地联调段落补充：三个分区、项目仅分组、置顶保持归属、删除保留会话、可见页一分钟及聚焦刷新；contracts.md 记录新 REST 入口，说明已存在摘要 SSE 未被前端接入。将完整设计仍标记为已确认，实施状态仅在实际验证通过后更新。
- [x] **8.5 交付验证记录。** 在本计划下方记录命令、退出码、测试通过数及受环境影响的未验证项。运行 `git status --short` 确认只含本任务相关变更，清理本次产生的无用 imports 和临时文件，不创建提交。

## 计划自检

- [x] 规格第 1～4 节交互范围 → 任务 5、7、8。
- [x] 规格第 5～6 节数据与模块职责 → 任务 1、2。
- [x] 规格第 7 节接口 → 任务 3、5。
- [x] 规格第 8 节事务并发 → 任务 2、4。
- [x] 规格第 9 节同步与失败 → 任务 6、7。
- [x] 规格第 10～11 节迁移兼容与验收 → 任务 1、3、8。
- [x] 新类型、接口与任务依赖已列出；未引入额外产品能力。

## 执行状态

用户选择第 2 种方式：当前会话顺序执行。2026-09-16 已完成实现、回归、浏览器验收和临时实例清理；保留工作区改动，没有创建分支、提交或推送。

### 实施时的调整

- 任务 3 的控制器、响应空值、请求校验和查询一致性测试合并到 agent-server 的 ConversationOrganizationIntegrationTest，使用真实 Spring HTTP 装配与独立 PostgreSQL，避免 standalone MockMvc 与实际 Jackson 配置不同。已有契约及 View 测试继续保留。
- 任务 4 使用 CountDownLatch 控制事务，并查询 pg_stat_activity 确认等待行锁。覆盖删除先于移入/新建、新建先于删除、删除回滚，以及一次聚合读取中穿插删除时的 REPEATABLE_READ 快照。运行保留通过真实消息提交加模拟执行引擎验证。
- 部分补充回归断言在实现后加入；核心迁移、持久化、前端状态与组件经历红绿验证。键盘焦点回归先复现失败，再阻止 Escape 冒泡，确认通过。
- HTTP 缺少必填 JSON 属性原先落入兜底 500，现归入统一 400 参数错误边界。未改变错误协议。
- 项目冲突后保留名称草稿，重试使用刷新后的项目版本；保存已成功但读取失败时关闭已完成的编辑，避免重复创建。
- 按用户选择在当前任务内顺序实施并自检，不使用子代理；按仓库要求保留未提交改动。

### 验证记录

| 验证 | 结果 |
| --- | --- |
| 持久化聚焦：ConversationProjectSchemaMigrationTest、ConversationOrganizationPersistenceTest | 4 项通过；退出码 0 |
| 初轮应用服务：ConversationOrganizationServiceTest、ConversationProjectApplicationServiceTest | 5 项通过；退出码 0 |
| REST 与契约：ConversationOrganizationIntegrationTest、ContractPathsTest、ContractExamplesTest、ApiExceptionHandlerTest | 通过；退出码 0，随后集成测试扩展至 7 项 |
| 完整后端：mvnw -f backend/pom.xml -B -pl agent-server -am test | 278 项：275 通过、3 跳过、0 失败/错误；退出码 0 |
| 补充边界与打包：同一 reactor，-Dtest=ConversationOrganizationIntegrationTest,ConversationProjectApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false package | 10 项通过（7 + 3），可执行 JAR 打包成功；退出码 0 |
| 完整前端：npm --prefix frontend test | 11 个文件、50 项通过；退出码 0 |
| 最终前端：npm --prefix frontend run build | Prettier、TypeScript 与 Vite 构建通过；退出码 0 |
| 仅本次 Java 文件的 Spotless 格式化、git diff --check | 通过；退出码 0 |

完整后端跳过的是仓库已有的两个 Codex 真实调用测试和一个 Ollama 长上下文测试，均由原有显式开关控制，本次未启用。未新增测试跳过或禁用容器验证。

### 浏览器验收

使用当前构建的独立后端和一次性 PostgreSQL，通过 Vite 同源代理联调；原有 8080 服务保持运行。

- 桌面 1280×720 与窄屏 320×800 已查看实际截图：分组、行操作、空项目、选中状态和抽屉无重叠。
- 验证项目创建、中文改名、项目内创建会话、会话改名、刷新持久化。
- 验证置顶只出现一次并显示来源；移动置顶会话仅改变来源；取消置顶回所属项目。
- 验证删除确认文案；删除项目后会话总数不变，非置顶会话转入未分组，置顶保留。
- 第二标签页重新可见后同步项目名称；后台浏览器不产生真实窗口聚焦事件的限制由临时显示测试页处理。
- 页面控制台无 error。输入法组合态、冲突、旧响应隔离、消息/Workflow 保留及写入中继续提交消息由前端自动化覆盖。
- 未在浏览器调用真实模型生成；运行中删除不终止执行已由 PostgreSQL 集成测试验证。
- 临时浏览器标签页、视口覆盖、Vite、测试后端及一次性数据库已清理。
- 收尾时发现其他工作新增了已暂存的 `docs/superpowers/specs/2026-09-16-agent-git-design.md`，未读取、修改或撤销该变更。
