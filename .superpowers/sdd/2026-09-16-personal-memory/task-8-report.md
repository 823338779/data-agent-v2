# 任务 8 实现报告：个人记忆管理 API、View 与错误映射

## 状态

DONE。实现基于 `develop@2801c64`，未创建 worktree，未派生实现或评审代理。外部修改 `docs/git-repository-setup.md` 未读取、未修改、未暂存、未回滚。

## 实现内容

- 在 `agent-web` 增加个人记忆管理的六个 REST 端点，Controller 请求只接受接口字段，不接受 tenantId、userId、reviewMode 或证据字段。
- 增加 `PersonalMemoryApplicationService`，统一构造 `Caller.UI` 的可信写入上下文，以 `USER_REQUESTED` 和空聊天证据调用既有 `MemoryService`；不访问 Entity、Mapper 或 MyBatis API。
- 将 `MemoryService.execute` 的失败 outcome 转为只含稳定业务码和安全文案的 `MemoryException`；成功 outcome 原样返回，因而 `ALREADY_EXISTS/created=false` 不会被误报为新建或恢复。
- 增加公开条目、分类列表和设置 View。公开条目不包含 normalizedHash、normalizationVersion、charCount、promptVersion、删除抑制或证据内容；updatedAt 按明确的 `Asia/Shanghai` 业务时区输出 ISO-8601 offset 文本。
- 列表从单个 `MemorySnapshot` 派生，按 `memoryId` 稳定升序分页；两个分类分别计算 `nextCursor`。kind 过滤时未请求分类仍保留空组；quotas 始终使用完整快照库存，不以当前页重新计算。
- `ApiExceptionHandler` 增加 `MemoryException` 的 ProblemDetail 映射，并把 query enum 转换失败纳入既有参数校验分支，避免非法 kind 落入 500。
- `agent-web` test scope 增加 `spring-boot-starter-webmvc-test`，保持 HTTP 契约测试归属 web 模块。

## 接口契约（供后续前端任务使用）

### `GET /api/user/memories`

查询参数：可选 `kind=person_profile|collaboration_preference`、可选稳定 ID `cursor`、`limit` 默认 20 且范围 1～100。响应固定为：

```json
{
  "groups": {
    "person_profile": {"entries": [], "nextCursor": null},
    "collaboration_preference": {"entries": [], "nextCursor": null}
  },
  "quotas": {
    "person_profile": {"used": 0, "limit": 800},
    "collaboration_preference": {"used": 0, "limit": 1500}
  },
  "revision": 0
}
```

entries 字段为 `memoryId`、`kind`、`content`、`source`、`userProtected`、`version`、`updatedAt`。`kind` 使用小写稳定值；`source` 沿用领域枚举 wire 值（如 `USER_UI`）；`updatedAt` 示例为 `2026-09-16T10:30:00+08:00`。页面必须沿每个分类自己的 `nextCursor` 继续请求，不能把首 20 条当完整库存。

### 写接口

- `POST /api/user/memories`：必需 `Idempotency-Key`；body 为 `kind`、`content`、可选 `restore`。`CREATED` 返回 201；`ALREADY_EXISTS` 返回 200。
- `PUT /api/user/memories/{id}`：必需 `Idempotency-Key`；body 为 `content`、`expectedVersion >= 1`；成功返回 200。
- `DELETE /api/user/memories/{id}?expectedVersion=...`：必需 `Idempotency-Key`，`expectedVersion >= 1`；成功返回 204 且无 body。

POST/PUT 的成功 body 直接使用 `MemoryOutcome`：`status`、`memoryId`、`version`、`revision`、`created`、`targetExists`、`quotas`。这保留了去重和跨分类恢复边界的真实结果：只有 `CREATED/created=true` 表示实际新建；`ALREADY_EXISTS/created=false` 只是确认已有条目。

正文仅做非空 Bean Validation，不在 DTO 上使用分类配额 `@Size`；去重后的容量、安全和最终库存判断继续由统一写入服务完成。

### 设置接口

- `GET /api/user/memories/settings`：返回 `settingsVersion`、`readEnabled`、`explicitWriteEnabled`、`autoLearningEnabled`。关闭 read 不阻止此管理读取，也不阻止库存列表。
- `PUT /api/user/memories/settings`：必需 `Idempotency-Key`；body 的 `expectedVersion >= 0` 必填，三个开关可空且空表示保持原值。设置版本与条目 version 完全分离；该入口不受 explicitWriteEnabled 阻断，允许重新开启设置。

### 错误映射

ProblemDetail 类型为 `urn:data-agent:problem:memory`，title 为 `Memory operation failed`，扩展字段 `code` 使用 `MemoryStatus.name()`。映射为：

- 400：`INVALID_REQUEST`、`UNSAFE_CONTENT`，以及缺头、JSON/参数形状、非法 enum 等校验失败。
- 404：`NOT_FOUND`。
- 409：`CONFLICT`、`DUPLICATE_CONFLICT`、`PROTECTED`、`SUPPRESSED`、`LEARNING_DISABLED`、`LEASE_LOST`。
- 422：`QUOTA_EXCEEDED`。
- 503：`UNAVAILABLE`。

响应 detail 只来自稳定安全文案，不含个人正文、数据库异常或证据原文。

## 变更文件

- `backend/agent-web/pom.xml`
- `backend/agent-web/src/main/java/com/datagent/agentv2/controller/PersonalMemoryController.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/memory/PersonalMemoryApplicationService.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/memory/PersonalMemoryView.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/memory/PersonalMemoryListView.java`
- `backend/agent-web/src/main/java/com/datagent/agentv2/memory/MemorySettingsView.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/memory/PersonalMemoryControllerTest.java`
- `backend/agent-web/src/test/java/com/datagent/agentv2/memory/PersonalMemoryViewTest.java`
- `backend/agent-server/src/main/java/com/datagent/agentv2/server/web/ApiExceptionHandler.java`
- `backend/agent-server/src/test/java/com/datagent/agentv2/memory/MemoryProblemDetailTest.java`

## TDD 证据

### 初始 RED

命令：

```text
mvn -f backend/pom.xml -pl agent-server -am '-Dtest=PersonalMemoryControllerTest,PersonalMemoryViewTest,MemoryProblemDetailTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

真实结果：`BUILD FAILURE`，exit 1。`agent-web` 在 `PersonalMemoryControllerTest.java:[15,39]` testCompile 失败：找不到 `com.datagent.agentv2.controller.PersonalMemoryController`。这是测试先于管理 API 实现的预期功能缺失。

### 迭代 RED/GREEN

- 第一轮实现后测试发现缺省 `restore` 被 primitive boolean 的严格 Jackson 反序列化当作 null→primitive 错误；改为可空 Boolean 并在 Controller 归一化，保持 API 字段可选。
- entry query version=0 首轮越过 standalone 的方法校验并触发领域 `IllegalArgumentException`；确认 Spring MVC 7 原生方法校验要求 Controller 不使用类级 `@Validated`，移除后 `@Min(1)` 正确在 HTTP 边界返回 400。
- 时间契约 RED：`PersonalMemoryViewTest` testCompile 明确报 `String 无法转换为 LocalDateTime`；改为明确业务时区的 ISO offset String 后 3/3 GREEN。
- query enum RED：`MemoryProblemDetailTest` 中 `work_context` 首先真实返回 500；纳入类型转换异常映射后 12/12 GREEN。
- 稳定 kind RED：`PersonalMemoryControllerTest` 中 `kind=person_profile` 首先真实返回 400（Spring 默认 `Enum.valueOf` 不识别小写 wire 值）；改由 Controller 调用 `MemoryKind.parse` 后 13/13 GREEN。

### 最终 GREEN

命令：

```text
mvn -f backend/pom.xml -pl agent-server -am '-Dtest=PersonalMemoryControllerTest,PersonalMemoryViewTest,MemoryProblemDetailTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`BUILD SUCCESS`，exit 0；`PersonalMemoryControllerTest` 13、`PersonalMemoryViewTest` 3、`MemoryProblemDetailTest` 12，共 28 个测试通过，0 失败、0 错误、0 跳过。15 个 reactor 模块均成功。

Spotless 使用逗号分隔的精确 Java 文件名正则，只格式化本任务 9 个 Java 文件；未运行无过滤 module apply。`git diff --check` 通过。

## 跳过、告警与顾虑

- 按任务要求只执行管理 API/View/ProblemDetail 的匹配范围验证，未重复无关后端全量套件，也未提前实现任务 9～12。
- 测试输出保留仓库既有的 Mockito/Byte Buddy 动态 agent、javac 注解处理器、既有 deprecated API 提示；Spotless 保留 `expandWildcardImports` 依赖解析告警。它们均未造成编译或测试失败。
- 自审覆盖六端点、UI context、entry/settings 版本下界、分页、完整配额、关闭开关恢复边界、错误状态与敏感字段排除。未发现遗留正确性顾虑。
