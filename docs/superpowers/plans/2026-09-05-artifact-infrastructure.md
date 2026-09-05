# Artifact 基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 Office 文档产出的第一个垂直切片：建立 Artifact 领域、持久化、本机不可变存储、沙箱选择契约、消息附件投影、受控 UI Block、文件卡片和安全下载链路，并用固定测试文件验证端到端交付；本切片不生成真实 Word/PPT/XLSX，也不注册 `artifact_build` Tool 或正式文档 Skill。

**Architecture:** 新增进程内 Maven Module `agent-artifact`，负责配置、沙箱 SPI、本机存储、发布事务和查询门面；Artifact Entity、Mapper 与 MyBatis-Plus Service 仍统一位于 `agent-persistence`。`agent-web` 批量组装消息附件及 UI Block，并提供按租户和会话校验的流式下载接口。前端把 Markdown 中严格匹配的 `<agent-ui>` 占位符切成受控片段，通过版本化 React 注册表渲染文件卡片，绝不启用原始 HTML。

**Tech Stack:** Java 21、Maven Reactor、Spring Boot 4.1、Spring MVC、MyBatis-Plus 3.5、Flyway、PostgreSQL、MapStruct、Jackson 3、JUnit 5、AssertJ、Testcontainers、React 19、TypeScript 5.8、react-markdown 10、Vitest、Testing Library。

**Spec:** `docs/superpowers/specs/2026-09-05-office-artifact-skills-design.md`

## Global Constraints

- 不新增常驻进程、端口、消息队列、远程对象存储或独立 `artifact-worker` 服务。
- 本切片不实现 Python/LibreOffice 调用，不创建 Word/PPT/XLSX Skill，也不把 Artifact Tool 加入 `maximum` 或 `initial` Tool 集合。
- Linux/Windows 只落地沙箱平台识别、Runner SPI、选择和失败关闭测试；真实 rootless Podman 与 Restricted Token/Job Object 执行器分别在 Word 切片实现。
- `data-agent.artifact.enabled=false` 为默认值；开启后如果没有且仅有一个健康的当前平台 Runner，应用启动必须失败，不得退化到裸 `ProcessBuilder`。
- 模型、REST 请求和前端不能指定脚本路径、本机路径、存储 key、容器参数或下载 URL。
- PostgreSQL 只保存元数据、关系、规格和摘要；文件只存储在配置的本机受控根目录。
- 正式 Artifact 不可覆盖。发布必须从同一根目录的 staging 原子移动到 final；原子移动不可用时失败关闭。
- 下载必须同时校验当前租户、会话归属、Artifact READY 状态、实际大小和 SHA-256；跨会话或跨租户统一返回 404。
- 单表 CRUD 和查询使用 MyBatis-Plus `ServiceImpl` 与 Lambda Wrapper；不得新增普通等值查询 XML SQL。
- MessageView 的 Artifact/UI Block 查询必须批量完成，禁止按消息 N+1 查询。
- Markdown 继续不解析任意 HTML；不得引入 `rehype-raw`，不得使用 `dangerouslySetInnerHTML`。
- UI Block 仅支持服务端结构化数据和前端显式注册的 `type@schemaVersion`；未知类型显示安全回退，不执行 payload 中的代码或样式。
- 新增核心 Java 类型、关键字段、原子发布、路径约束、事务和失败关闭逻辑必须写准确中文注释。
- 每个生产行为按 RED → GREEN → REFACTOR 推进；先确认测试因目标能力缺失失败，再写最小实现。
- 仅修改本计划列出的范围；保留工作树中与本任务无关的用户变更。

---

## 文件结构总览

### 新增 Module：`backend/agent-artifact`

- `pom.xml`：依赖 `agent-domain`、`agent-persistence`、Spring Context/Jackson/Micrometer，测试依赖 Spring Boot Test。
- `config/ArtifactProperties.java`：功能开关、本机目录、限额、保留期和沙箱配置。
- `sandbox/SandboxPlatform.java`：`LINUX`、`WINDOWS`。
- `sandbox/OperatingSystemDetector.java`：把 `os.name` 严格归一为支持的平台。
- `sandbox/ArtifactSandboxRunner.java`：未来文档构建执行器的窄 SPI。
- `sandbox/SandboxProbe.java`、`SandboxRunRequest.java`、`SandboxRunResult.java`：自检和运行契约，输入中不包含任意命令行。
- `sandbox/ArtifactSandboxSelector.java`：当前平台唯一 Runner 选择与健康检查。
- `sandbox/ArtifactStartupValidator.java`：启用能力后的启动失败关闭。
- `storage/ArtifactStorage.java`：staging、原子发布、只读打开和清理 SPI。
- `storage/LocalArtifactStorage.java`：本机目录实现，封装路径、软链接、大小和原子移动边界。
- `storage/StagedArtifact.java`、`StoredArtifact.java`、`ArtifactReadHandle.java`：不暴露绝对路径的存储值对象。
- `application/ArtifactPublicationService.java`：正式文件发布、元数据/关联/UI Block 事务和消息版本更新。
- `application/ArtifactQueryService.java`：消息投影批量查询、下载鉴权与完整性校验。
- `application/ArtifactProjection.java`、`ArtifactDownload.java`：供 `agent-web` 使用的不可变读模型。
- `recovery/ArtifactRecoveryRunner.java`：启用时收敛中断任务并清理过期 staging。

### 修改 Module：`backend/agent-domain`

- `artifact/domain/*`：Artifact 类型、状态、任务、消息关系和 UI Block 领域值对象。
- `conversation/domain/event/ConversationMessageChanged.java`：把消息变化事件从 `agent-web` 下沉到领域层，使 `agent-artifact` 可发布而不反向依赖 Web。

### 修改 Module：`backend/agent-persistence`

- `db/migration/V4__add_artifact_delivery_foundation.sql`：四张表、约束、索引和中文数据库注释。
- `entity/artifact/*`：`ArtifactBuildJobEntity`、`ArtifactObjectEntity`、`ConversationMessageArtifactEntity`、`MessageUiBlockEntity`。
- `mapper/artifact/*Mapper.java`：四个 `BaseMapper`。
- `service/artifact/*Service.java`：基于 `ServiceImpl` 的持久化服务与批量查询。
- `mapping/ArtifactPersistenceMapper.java`：MapStruct 纯转换。

### 修改 Module：`backend/agent-web`

- `pom.xml`：依赖 `agent-artifact`。
- `conversation/session/ConversationView.java`：MessageView 增加 `artifacts` 与 `uiBlocks`。
- `conversation/session/ConversationApplicationService.java`：批量组装 Artifact 投影。
- `conversation/process/*`、`conversation/stream/ConversationEventStreamFacade.java`：改用下沉后的消息变化事件。
- `artifact/ArtifactController.java`：安全下载端点。

### 修改 Module：`backend/agent-server`

- `src/main/resources/application-business.yml`：新增默认关闭的 Artifact 配置。
- 启动测试：验证关闭可启动、开启且无 Runner 时失败、Fake Runner 健康时通过。

### 修改前端与契约

- `contracts/openapi.yaml`：MessageView 新字段和 Artifact 下载接口。
- `contracts/schemas/session-sse-event.schema.json`、`contracts/examples/**`：SSE/REST 消息附件示例。
- `frontend/src/domain/agent.ts`：Artifact、UI Block 类型。
- `frontend/src/api/backendAgentClient.ts`：向后兼容映射。
- `frontend/src/components/MarkdownContent.tsx`：受控占位符分段与 UI Block 渲染。
- `frontend/src/components/ui-block/*`：注册表、未知块回退与文件卡片。
- `frontend/src/styles.css`：文件卡片样式。

---

### Task 1: 建立领域契约并下沉消息变化事件

**Files:**
- Modify: `backend/agent-domain/pom.xml`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactType.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactStatus.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactBuildStatus.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactOperation.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactObject.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/ArtifactBuildJob.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/MessageArtifact.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/artifact/domain/MessageUiBlock.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/event/ConversationMessageChanged.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationMessageChanged.java`
- Modify imports in all current producers/listeners of `ConversationMessageChanged`
- Test: `backend/agent-domain/src/test/java/com/datagent/agentv2/artifact/domain/ArtifactDomainContractTest.java`

**Interfaces:**

```java
public enum ArtifactType {
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
}

public record MessageUiBlock(
        String uiBlockId,
        String conversationMessageId,
        String type,
        int schemaVersion,
        String variant,
        JsonNode payload,
        int displayOrder) {}
```

- [x] **Step 1: Write the failing domain contract test**

Assert exact MIME/extension mappings, terminal build states, immutable `List.copyOf` behavior where lists exist, and that `ConversationMessageChanged` is importable from `agent-domain` rather than `agent-web`.

- [x] **Step 2: Run the focused test and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain '-Dtest=ArtifactDomainContractTest' test
```

Expected: compilation fails because the Artifact types do not exist.

- [x] **Step 3: Add the enums/records/classes and move the event without changing its fields**

Keep `ConversationMessageChanged(tenantId, conversationSessionId, conversationMessageId, messageVersion)` binary semantics intact. Use records for immutable relation/UI values and explicit domain classes only where persistence updates require mutation. `ArtifactBuildStatus.isTerminal()` must return true only for `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`.

- [x] **Step 4: Update imports and run focused downstream tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain,agent-web -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ArtifactDomainContractTest,ConversationApplicationServiceTest' test
```

Expected: tests pass and `agent-domain` has no dependency on Web/Persistence.

- [x] **Step 5: Commit**

```powershell
git add backend/agent-domain backend/agent-web/src/main/java
git commit -m "feat: add artifact domain contracts"
```

---

### Task 2: 增加 Artifact 数据库迁移与约束验证

**Files:**
- Create: `backend/agent-persistence/src/main/resources/db/migration/V4__add_artifact_delivery_foundation.sql`
- Create: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/migration/ArtifactSchemaMigrationTest.java`

**Schema:**

- `artifact_build_job`：业务 ID、tenant/session/execution/message、类型、operation、状态、specification JSONB、template/digest/runtime/platform、失败码、开始/完成时间、审计/软删除/版本。
- `artifact_object`：不可变业务 ID、来源 Artifact、spec version、类型、READY 状态、display name、media type、storage key、size、sha256、页/幻灯片/工作表数、审计字段。
- `conversation_message_artifact`：消息与 Artifact 关联、展示顺序；同一消息同一 Artifact 唯一。
- `message_ui_block`：消息、block ID、type、schema version、variant、payload JSONB、展示顺序；block ID 全局唯一。

- [x] **Step 1: Write a PostgreSQL Testcontainers migration test before adding V4**

The test must query `information_schema.columns`, `pg_constraint`, `pg_indexes` and `pg_description`. Assert all four tables, tenant/session/message indexes, unique business IDs, positive size/count checks, status checks, foreign keys, and non-empty Chinese comments on every business column.

- [x] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ArtifactSchemaMigrationTest' test
```

Expected: test fails because V4 and the four tables are absent.

- [x] **Step 3: Create V4 with explicit checks and indexes**

Representative constraints:

```sql
constraint ck_artifact_type check (artifact_type in ('DOCX', 'PPTX', 'XLSX')),
constraint ck_artifact_ready check (status = 'READY'),
constraint ck_artifact_size check (size_bytes > 0),
constraint ck_artifact_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
constraint uk_message_artifact unique (tenant_id, conversation_message_id, artifact_id),
constraint uk_message_ui_block_id unique (tenant_id, ui_block_id)
```

Use `comment on table` and `comment on column` for tables, business keys, tenant/audit/version/soft-delete fields. Foreign keys must bind tenant-owned session, execution, message and Artifact rows without introducing cascade deletion of formal files.

- [x] **Step 4: Run migration and existing schema tests for GREEN**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ArtifactSchemaMigrationTest,ConversationExecutionSchemaMigrationTest' test
```

Expected: both migration suites pass on PostgreSQL.

- [x] **Step 5: Commit**

```powershell
git add backend/agent-persistence/src/main/resources/db/migration/V4__add_artifact_delivery_foundation.sql `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/migration/ArtifactSchemaMigrationTest.java
git commit -m "feat: add artifact persistence schema"
```

---

### Task 3: 实现 MyBatis-Plus Artifact 持久化层

**Files:**
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/artifact/*.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapper/artifact/*.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/artifact/ArtifactBuildJobService.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/artifact/ArtifactObjectService.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/artifact/ConversationMessageArtifactService.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/artifact/MessageUiBlockService.java`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ArtifactPersistenceMapper.java`
- Test: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/service/artifact/ArtifactPersistenceServiceTest.java`

- [x] **Step 1: Write failing persistence service tests**

Use a real PostgreSQL/MyBatis-Plus test context because `ServiceImpl.lambdaQuery()` requires Mapper proxy metadata that a plain Mockito Mapper does not provide. Cover: batch relations by message IDs preserve `display_order`; UI Block batch query uses the same tenant boundary; empty collections do not query; interrupted non-terminal jobs transition only from the four active states.

- [x] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ArtifactPersistenceServiceTest' test
```

- [x] **Step 3: Implement entities, BaseMapper and ServiceImpl classes**

Use `@TableName(autoResultMap = true)`, `@Getter`, `@Setter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` and existing `TenantAuditedEntity`. JSONB fields use the project `JsonbStringTypeHandler`. Example batch method:

```java
public List<ConversationMessageArtifactEntity> listByMessageIds(Collection<String> messageIds) {
    if (messageIds.isEmpty()) {
        return List.of();
    }
    return list(new LambdaQueryWrapper<ConversationMessageArtifactEntity>()
            .in(ConversationMessageArtifactEntity::getConversationMessageId, messageIds)
            .orderByAsc(ConversationMessageArtifactEntity::getConversationMessageId)
            .orderByAsc(ConversationMessageArtifactEntity::getDisplayOrder));
}
```

Do not add custom XML. Use a Spring component-model MapStruct mapper for isomorphic fields; explicitly map enum string fields and JSON payload/specification.

- [x] **Step 4: Run persistence tests and formatting**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dtest=ArtifactPersistenceServiceTest,ConversationMessageServiceTest' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence spotless:check
```

Expected: tests pass; no mapper is accessed outside `agent-persistence`.

- [x] **Step 5: Commit**

```powershell
git add backend/agent-persistence
git commit -m "feat: persist artifact metadata and message blocks"
```

---

### Task 4: 建立 `agent-artifact` Module、配置和沙箱失败关闭契约

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-artifact/pom.xml`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/config/ArtifactProperties.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/SandboxPlatform.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/OperatingSystemDetector.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/ArtifactSandboxRunner.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/SandboxProbe.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/SandboxRunRequest.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/SandboxRunResult.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/ArtifactSandboxSelector.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/sandbox/ArtifactStartupValidator.java`
- Modify: `backend/agent-server/src/main/resources/application-business.yml`
- Test: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/sandbox/OperatingSystemDetectorTest.java`
- Test: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/sandbox/ArtifactSandboxSelectorTest.java`

**SPI:**

```java
public interface ArtifactSandboxRunner {
    SandboxPlatform platform();
    SandboxProbe probe();
    SandboxRunResult run(SandboxRunRequest request) throws InterruptedException;
    void cancel(String buildJobId);
}

public record SandboxRunRequest(
        String buildJobId,
        ArtifactType artifactType,
        String fixedEntrypoint,
        String inputStorageKey,
        String outputStorageKey,
        Duration timeout,
        long maxOutputBytes) {}
```

`fixedEntrypoint` is an application enum-backed identifier such as `word/build`, never a command or path supplied by the model. Before later production use, replace it with a dedicated enum if more than three values appear.

- [ ] **Step 1: Write RED tests for platform selection and fail-closed behavior**

Cover `Linux`, `Windows 11`, case/whitespace; reject macOS/unknown; reject zero or two matching runners; reject unhealthy probe; do not probe or require a runner when feature is disabled; choose exactly one healthy current-platform runner.

- [ ] **Step 2: Run and confirm RED because the Module is absent**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact test
```

- [ ] **Step 3: Add the Module and strict properties**

Defaults:

```yaml
data-agent:
  artifact:
    enabled: false
    root-directory: ${DATA_AGENT_ARTIFACT_ROOT:./data/artifacts}
    max-concurrent-builds: 1
    max-build-duration: 180s
    max-build-attempts-per-execution: 3
    max-input-bytes: 20971520
    max-output-bytes: 52428800
    min-free-disk-bytes: 1073741824
    staging-retention: 24h
```

Use `@ConfigurationProperties("data-agent.artifact")` with Bean Validation: positive sizes/durations, max attempts 1–3, and a non-null root. Do not add `unsafe`, `none`, arbitrary command, network, image, or launcher arguments.

- [ ] **Step 4: Implement selector/startup validator and run GREEN**

`ArtifactStartupValidator` runs only when enabled, validates the storage root and invokes `ArtifactSandboxSelector.requireHealthyRunner()`. Its exception must name the platform and stable reason but must not include credentials, environment dumps or paths outside the configured root.

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=OperatingSystemDetectorTest,ArtifactSandboxSelectorTest' test
```

- [ ] **Step 5: Commit**

```powershell
git add backend/pom.xml backend/agent-artifact backend/agent-server/src/main/resources/application-business.yml
git commit -m "feat: add artifact sandbox contracts"
```

---

### Task 5: 实现不可越界的本机 staging 与原子发布存储

**Files:**
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage/ArtifactStorage.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage/LocalArtifactStorage.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage/StagedArtifact.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage/StoredArtifact.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage/ArtifactReadHandle.java`
- Test: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/storage/LocalArtifactStorageTest.java`

- [ ] **Step 1: Write failing temporary-directory tests**

Cover: generated storage keys only; same-root staging; publish produces `ready/{artifactId}/output.{ext}`; existing final file is never overwritten; `..`, absolute paths, wrong extension and symlink escapes are rejected; size limit is enforced while writing; SHA-256 and size are recomputed while opening; atomic-move failure leaves no READY handle.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=LocalArtifactStorageTest' test
```

- [ ] **Step 3: Implement the narrow storage API**

```java
public interface ArtifactStorage {
    StagedArtifact createStaging(String buildJobId, ArtifactType type) throws IOException;
    StoredArtifact publish(String artifactId, StagedArtifact staged) throws IOException;
    ArtifactReadHandle openReady(String storageKey, long expectedSize, String expectedSha256)
            throws IOException;
    int deleteStagingOlderThan(Instant cutoff) throws IOException;
}
```

Resolve with `root.resolve(relative).normalize()` and require `candidate.startsWith(root)`. Reject every existing symlink component with `Files.isSymbolicLink`. Write through a counting/digest stream into `staging/{buildJobId}/output.{ext}`. Publish only using `Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)` with no `REPLACE_EXISTING`. Keep display names exclusively in metadata; never use them as filesystem names.

- [ ] **Step 4: Run GREEN on Windows and keep tests platform-neutral**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=LocalArtifactStorageTest' test
```

Expected: all storage checks pass without requiring Python, LibreOffice, Podman or native Windows launchers.

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/storage `
  backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/storage
git commit -m "feat: add immutable local artifact storage"
```

---

### Task 6: 实现 Artifact 元数据投影和批量查询门面

**Files:**
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/ArtifactProjection.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/MessageArtifactProjection.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/MessageUiBlockProjection.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/MessageArtifactBundle.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/ArtifactQueryService.java`
- Test: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/application/ArtifactQueryServiceTest.java`

**Public read model:**

```java
public record ArtifactProjection(
        String artifactId,
        ArtifactType artifactType,
        String displayName,
        String mediaType,
        long sizeBytes,
        String sha256,
        Integer pageOrSheetCount,
        String downloadUrl) {}

public record MessageArtifactBundle(
        List<ArtifactProjection> artifacts,
        List<MessageUiBlockProjection> uiBlocks) {
    public static MessageArtifactBundle empty() { return new MessageArtifactBundle(List.of(), List.of()); }
}
```

- [ ] **Step 1: Write a RED test proving three bulk queries, not N+1**

For three message IDs, verify one call for message-artifact relations, one `IN` call for Artifact objects, and one call for UI Blocks. Assert stable order and that download URLs are server-derived as `/api/v1/agent-sessions/{sessionId}/artifacts/{artifactId}/content`.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactQueryServiceTest' test
```

- [ ] **Step 3: Implement batch assembly in application memory**

Expose:

```java
Map<String, MessageArtifactBundle> findByMessageIds(
        String conversationSessionId, Collection<String> conversationMessageIds);

Optional<ArtifactDownload> findDownload(
        String conversationSessionId, String artifactId);
```

The download query must join through the message relation and verify the related message belongs to the requested session. Return `Optional.empty()` for missing/wrong-session/wrong-tenant/not-READY; do not reveal which check failed. Do not expose `storageKey` through projection records.

- [ ] **Step 4: Run GREEN**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactQueryServiceTest,ArtifactPersistenceServiceTest' test
```

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application `
  backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/application
git commit -m "feat: query artifact message projections"
```

---

### Task 7: 实现固定测试产物的事务发布闭环

**Files:**
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/ArtifactPublicationRequest.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/ArtifactPublicationResult.java`
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/application/ArtifactPublicationService.java`
- Create: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/application/ArtifactPublicationServiceTest.java`
- Modify if needed: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`

- [ ] **Step 1: Write RED tests for transaction ordering and compensation**

Cover: only a completed staged file can publish; storage move happens before READY metadata; transaction inserts Artifact, relation and `artifact-card@1` UI Block; assistant message is locked by execution ID and touched through existing optimistic update; event is published with the new version; duplicate artifact/job publication is rejected; DB failure after move invokes exact-final-file cleanup and never returns success.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactPublicationServiceTest' test
```

- [ ] **Step 3: Implement publication with a narrow internal request**

```java
public record ArtifactPublicationRequest(
        String buildJobId,
        String executionId,
        ArtifactType artifactType,
        String displayName,
        JsonNode specification,
        int specificationVersion,
        Integer pageOrSheetCount,
        StagedArtifact stagedArtifact) {}
```

The service derives all IDs with `IdGenerator`, sanitizes `displayName` to a basename with the expected extension, and creates UI payload containing only `artifactId`. Use `@Transactional` for database writes and event publication. If the transaction callback throws after the move, delete only the exact `ready/{newArtifactId}` path returned by `StoredArtifact`; never accept a caller-computed cleanup path.

- [ ] **Step 4: Run GREEN and verify no Web dependency**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactPublicationServiceTest' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact dependency:tree
```

Expected: publication tests pass; dependency tree contains no `agent-web` or `agent-server`.

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-artifact backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java
git commit -m "feat: publish artifacts to assistant messages"
```

---

### Task 8: 把 Artifact 与 UI Block 加入 REST/SSE 消息投影

**Files:**
- Modify: `backend/agent-web/pom.xml`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationView.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationApplicationService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/session/ConversationViewCompositionTest.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/session/ConversationApplicationServiceTest.java`

- [ ] **Step 1: Extend tests first**

Add assertions that snapshot/history/message-update views include ordered `artifacts` and `uiBlocks`, missing relations become empty lists, and fetching a session with multiple messages invokes one batch artifact lookup. Existing execution/thinking projection assertions must remain unchanged.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=ConversationViewCompositionTest,ConversationApplicationServiceTest' test
```

- [ ] **Step 3: Extend `MessageView` and its semantic factory**

```java
public record MessageView(
        String conversationMessageId,
        String executionId,
        MessageRole role,
        String author,
        String content,
        long version,
        List<ThinkingProcessItem> thinkingProcess,
        List<ArtifactProjection> artifacts,
        List<MessageUiBlockProjection> uiBlocks,
        LocalDateTime createdAt,
        AgentExecutionView execution) {

    public static MessageView composeFromMessage(
            ConversationMessage message,
            AgentExecutionView execution,
            MessageArtifactBundle artifactBundle) { /* pure construction */ }
}
```

Keep an overload `composeFromMessage(message, execution)` delegating to `MessageArtifactBundle.empty()` only if it materially reduces test churn; production query paths must always pass the bulk-loaded bundle.

- [ ] **Step 4: Batch-load once in every list/snapshot path and once in single-message path**

Introduce one private helper that receives all `ConversationMessage` values, calls `ArtifactQueryService.findByMessageIds(...)` once, and builds views. `messageViewById` may issue a one-message batch call. The existing after-commit `ConversationMessageChanged` listener then automatically emits the new card via `message_update`.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=ConversationViewCompositionTest,ConversationApplicationServiceTest' test
git add backend/agent-web
git commit -m "feat: expose artifact blocks in message views"
```

---

### Task 9: 提供会话级安全下载端点并更新契约

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/artifact/ArtifactController.java`
- Test: `backend/agent-web/src/test/java/com/datagent/agentv2/artifact/ArtifactControllerTest.java`
- Modify: `contracts/openapi.yaml`
- Modify: `contracts/schemas/session-sse-event.schema.json`
- Modify: `contracts/examples/rest/session-create.yaml`
- Modify: `contracts/examples/rest/session-running.yaml`
- Modify: `contracts/examples/sse/session-snapshot.json`
- Modify: `contracts/examples/sse/message-update.json`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractExamplesTest.java`

- [ ] **Step 1: Write MVC tests before the controller**

Assert 200 headers/body streaming, RFC 5987 UTF-8 filename, exact MIME, `Content-Length`, quoted `ETag`; 404 for missing/wrong session/not READY; 409 or 500 with a stable safe problem response for size/SHA mismatch; no response contains `storageKey` or an absolute path.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=ArtifactControllerTest' test
```

- [ ] **Step 3: Implement streaming download**

```java
@GetMapping("/api/v1/agent-sessions/{sessionId}/artifacts/{artifactId}/content")
public ResponseEntity<StreamingResponseBody> download(
        @PathVariable String sessionId, @PathVariable String artifactId) {
    ArtifactDownload download = artifacts.findDownload(sessionId, artifactId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.mediaType()))
            .contentLength(download.sizeBytes())
            .eTag('"' + download.sha256() + '"')
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(download.displayName(), UTF_8).build().toString())
            .body(output -> download.copyTo(output));
}
```

`ArtifactDownload.copyTo` must open through `ArtifactStorage.openReady`, verify digest/size before sending the first byte by preflight validation, and close the stream. For the 50 MiB ceiling, do not materialize the entire file in heap.

- [ ] **Step 4: Update OpenAPI/SSE schemas and valid examples**

Define `ArtifactView`, `MessageUiBlockView` and `artifact-card@1` payload. The OpenAPI path documents 200/404/integrity failure. Examples include one card marker in content and matching structured block/artifact arrays; no storage key appears.

- [ ] **Step 5: Run controller and contract tests, then commit**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=ArtifactControllerTest,ContractExamplesTest,ContractPathsTest' test
git add backend/agent-web contracts
git commit -m "feat: add secure artifact downloads"
```

---

### Task 10: 实现前端 Artifact 类型与向后兼容协议映射

**Files:**
- Modify: `frontend/src/domain/agent.ts`
- Modify: `frontend/src/api/backendAgentClient.ts`
- Modify: `frontend/src/api/backendAgentClient.test.ts`

**Types:**

```typescript
export type ArtifactType = 'DOCX' | 'PPTX' | 'XLSX';

export interface MessageArtifact {
  artifactId: string;
  artifactType: ArtifactType;
  displayName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  pageOrSheetCount: number | null;
  downloadUrl: string;
}

export interface MessageUiBlock {
  blockId: string;
  type: string;
  schemaVersion: number;
  variant: string;
  payload: unknown;
}
```

- [ ] **Step 1: Add RED client mapping tests**

Test REST snapshot and `message_update` mapping. New fields pass through; legacy payloads with absent fields map to `artifacts: []` and `uiBlocks: []`; untrusted download URLs are not synthesized client-side.

- [ ] **Step 2: Run and confirm RED**

```powershell
Set-Location .\frontend
npm.cmd test -- backendAgentClient.test.ts
Set-Location ..
```

- [ ] **Step 3: Implement strict but backward-compatible mapping**

Add runtime guards for Artifact type, positive size, block ID/type/schema version and same-origin relative download URL beginning with `/api/v1/agent-sessions/`. Invalid items are omitted rather than rendered. Keep message mapping pure:

```typescript
function toMessage(message: BackendMessage): ConversationMessage {
  return {
    // existing fields
    artifacts: (message.artifacts ?? []).flatMap(toArtifact),
    uiBlocks: (message.uiBlocks ?? []).flatMap(toUiBlock),
  };
}
```

- [ ] **Step 4: Run GREEN and commit**

```powershell
Set-Location .\frontend
npm.cmd test -- backendAgentClient.test.ts
Set-Location ..
git add frontend/src/domain/agent.ts frontend/src/api/backendAgentClient.ts frontend/src/api/backendAgentClient.test.ts
git commit -m "feat: map artifact message metadata"
```

---

### Task 11: 实现受控 Markdown UI Block 解析与版本化注册表

**Files:**
- Create: `frontend/src/components/ui-block/parseAgentUiPlaceholders.ts`
- Create: `frontend/src/components/ui-block/parseAgentUiPlaceholders.test.ts`
- Create: `frontend/src/components/ui-block/UiBlockRenderer.tsx`
- Create: `frontend/src/components/ui-block/UiBlockRenderer.test.tsx`
- Modify: `frontend/src/components/MarkdownContent.tsx`
- Create: `frontend/src/components/MarkdownContent.test.tsx`

- [ ] **Step 1: Write RED parser and renderer tests**

Cover exact double-quoted marker, surrounding Markdown preservation, malformed/single-quoted/extra-attribute tags staying inert, duplicate marker rendering once, missing block, unknown `type@version`, raw `<script>` text never executing, and unreferenced artifacts being appended once below Markdown.

- [ ] **Step 2: Run and confirm RED**

```powershell
Set-Location .\frontend
npm.cmd test -- parseAgentUiPlaceholders.test.ts UiBlockRenderer.test.tsx MarkdownContent.test.tsx
Set-Location ..
```

- [ ] **Step 3: Implement strict segmentation without an HTML parser**

Use a fixed scanner/regex only for this grammar:

```typescript
const PLACEHOLDER = /<agent-ui block-id="([a-zA-Z0-9_-]{1,80})"><\/agent-ui>/g;

export type MarkdownSegment =
  | { kind: 'markdown'; content: string }
  | { kind: 'ui'; blockId: string };
```

Render every Markdown segment with the existing `<ReactMarkdown remarkPlugins={[remarkGfm]}>`; do not add `rehypeRaw`. The registry is explicit:

```typescript
const renderers: Record<string, UiBlockComponent> = {
  'artifact-card@1': ArtifactCardBlock,
};
```

Resolve `artifact-card@1` payload only as `{ artifactId: string }`, then find the matching Artifact from the separate structured array. Payload cannot override filename, MIME, size, URL, class name or style.

- [ ] **Step 4: Run GREEN and verify forbidden APIs are absent**

```powershell
Set-Location .\frontend
npm.cmd test -- parseAgentUiPlaceholders.test.ts UiBlockRenderer.test.tsx MarkdownContent.test.tsx
rg "rehype-raw|dangerouslySetInnerHTML" src
Set-Location ..
```

Expected: tests pass; `rg` returns no match and therefore exits with code 1.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/components
git commit -m "feat: render controlled markdown ui blocks"
```

---

### Task 12: 实现文件卡片、下载交互和消息接线

**Files:**
- Create: `frontend/src/components/ui-block/ArtifactCardBlock.tsx`
- Create: `frontend/src/components/ui-block/ArtifactCardBlock.test.tsx`
- Modify: `frontend/src/components/MarkdownContent.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: Write RED interaction tests**

Assert DOCX/PPTX/XLSX icons and labels, localized byte size, optional page/slide/sheet count, accessible file name, `<a download>` exact href, no click-time blob buffering, missing Artifact safe fallback, and keyboard-visible download action.

- [ ] **Step 2: Run and confirm RED**

```powershell
Set-Location .\frontend
npm.cmd test -- ArtifactCardBlock.test.tsx MarkdownContent.test.tsx
Set-Location ..
```

- [ ] **Step 3: Implement card and pass structured fields from `App.tsx`**

```tsx
<MarkdownContent
  content={visibleAssistantContent}
  artifacts={message.artifacts}
  uiBlocks={message.uiBlocks}
/>
```

Use `FileText`, `Presentation`, `Sheet`, `Download` from `lucide-react`. The action is a normal same-origin anchor:

```tsx
<a className="artifact-card__download" href={artifact.downloadUrl} download={artifact.displayName}>
  <Download aria-hidden="true" />
  <span>下载</span>
</a>
```

Do not use `fetch(...).blob()` or `URL.createObjectURL`. Apply existing color, spacing, focus and responsive conventions; keep the card usable at narrow widths.

- [ ] **Step 4: Run component suite and build**

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
Set-Location ..
```

Expected: all Vitest tests pass; TypeScript and Vite production build succeed.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add downloadable artifact cards"
```

---

### Task 13: 收敛中断任务和过期 staging

**Files:**
- Create: `backend/agent-artifact/src/main/java/com/datagent/agentv2/artifact/recovery/ArtifactRecoveryRunner.java`
- Test: `backend/agent-artifact/src/test/java/com/datagent/agentv2/artifact/recovery/ArtifactRecoveryRunnerTest.java`

- [ ] **Step 1: Write RED recovery tests**

Assert runner is absent when feature disabled; when enabled, startup changes only `PREPARED/RUNNING/RENDERING/VERIFYING` jobs to `FAILED` with `PROCESS_RESTARTED`, leaves terminal rows unchanged, and deletes only staging directories older than configured retention. A storage cleanup failure is logged/observed and does not delete database metadata.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactRecoveryRunnerTest' test
```

- [ ] **Step 3: Implement conditional startup recovery**

Use `ApplicationRunner` plus `@ConditionalOnProperty(prefix = "data-agent.artifact", name = "enabled", havingValue = "true")`. Persist failure status through `ArtifactBuildJobService` before storage cleanup. Emit only counts, durations and stable codes to logs/metrics; do not log specs, paths or filenames.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact -am `
  '-Dtest=ArtifactRecoveryRunnerTest' test
git add backend/agent-artifact
git commit -m "feat: recover interrupted artifact work"
```

---

### Task 14: 增加固定文件端到端交付与启动装配测试

**Files:**
- Modify: `backend/agent-server/pom.xml` if test fixtures require direct test dependency
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/artifact/ArtifactDeliveryIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/artifact/ArtifactStartupIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/artifact/FakeArtifactSandboxRunner.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/server/ModuleBoundaryTest.java`

- [ ] **Step 1: Write RED end-to-end and context tests**

`ArtifactDeliveryIntegrationTest` uses PostgreSQL Testcontainers and a temporary artifact root. Arrange a session/execution/assistant placeholder, create deterministic fixture bytes through `ArtifactStorage.createStaging`, publish them, then assert:

1. message query and `message_update` contain matching Artifact/UI Block;
2. marker is `<agent-ui block-id="..."></agent-ui>`;
3. download returns exactly the fixture bytes and headers;
4. a different session receives 404;
5. DB stores metadata/storage key but not binary contents.

`ArtifactStartupIntegrationTest` creates minimal `ApplicationContextRunner` contexts: disabled/no runner succeeds; enabled/no runner fails; enabled/unhealthy Fake fails; enabled/healthy current-platform Fake succeeds.

- [ ] **Step 2: Run and confirm RED**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ArtifactDeliveryIntegrationTest,ArtifactStartupIntegrationTest,ModuleBoundaryTest' test
```

- [ ] **Step 3: Complete only the missing wiring exposed by the tests**

Add `agent-artifact` after `agent-skill` in the reactor and as a direct dependency of `agent-web`; keep `agent-server` as the only executable. Register properties without narrowing `@ConfigurationPropertiesScan`; prefer `@EnableConfigurationProperties(ArtifactProperties.class)` in Artifact configuration. Update module boundary assertions:

- `agent-artifact` may depend on domain/persistence and Spring libraries;
- `agent-artifact` must not depend on web/server;
- `agent-web` may depend on `agent-artifact`;
- only `agent-persistence` may contain Artifact Mapper/MyBatis imports.

- [ ] **Step 4: Run the full relevant backend verification**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain,agent-persistence,agent-artifact,agent-web,agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-domain,agent-persistence,agent-artifact,agent-web,agent-server -am spotless:check
```

Expected: all selected reactor tests and Spotless checks pass.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "test: verify artifact delivery foundation"
```

---

### Task 15: 执行最终安全与范围验收

**Files:**
- Verify only; modify production files only if a verification reveals a defect covered by this plan.

- [ ] **Step 1: Run the complete backend reactor**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

Expected: `BUILD SUCCESS`; no real sandbox, Python, LibreOffice or network dependency is needed because Artifact remains disabled by default and tests use Fake Runner.

- [ ] **Step 2: Run the complete frontend suite/build**

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
Set-Location ..
```

Expected: Vitest, Prettier, TypeScript and Vite succeed.

- [ ] **Step 3: Run explicit security scans**

```powershell
rg "ProcessBuilder|Runtime\.getRuntime\(\)\.exec|cmd\.exe|powershell\.exe|/bin/(sh|bash)" backend/agent-artifact/src/main
rg "rehype-raw|dangerouslySetInnerHTML|createObjectURL|\.blob\(\)" frontend/src
rg "storageKey|rootDirectory" contracts frontend/src/domain frontend/src/api
```

Expected: first two scans return no matches. The third may match private backend-client transport names only if they are not part of public interfaces; preferred result is no match in contracts/frontend.

- [ ] **Step 4: Check dependency and repository scope**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-artifact dependency:tree
git status --short
git diff --check
```

Expected: no Web/Server dependency under `agent-artifact`; no generated files, credentials, local Artifact data, `target/`, `dist/` or unrelated changes are staged; `git diff --check` is clean.

- [ ] **Step 5: Record the slice boundary in the handoff**

State explicitly that this slice proves storage → metadata → message projection → UI Block → download, but does not yet claim real DOCX/PPTX/XLSX generation. The next implementation plan must start with the Word slice: audited Python runtime resources, Linux rootless Podman Runner, Windows Restricted Token/Job Object Runner, `word-artifact` Skill, `artifact_build`/`artifact_inspect`, render-and-verify QA, then repeat the verified pattern for XLSX and PPTX.
