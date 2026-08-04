# 以 Content 为中心的对话持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本仓库 `AGENTS.md` 明确禁止 subagent 和额外 worktree，因此只能在当前工作区内联执行。

**Goal:** 将一次真实问答严格持久化为一条 user 消息和一条 assistant 中心消息，并把 thinking、工具调用及结果作为同一 assistant 行中的强类型有序 `thinkingProcess` 保存、展示和审计。

**Architecture:** `conversation_message.content` 继续保存真实正文，新增 `thinking_process jsonb` 保存序列化后的 `List<ThinkingProcessItem>`；assistant 在提交或重试时先创建唯一占位行，ReAct 生命周期只更新该行。`agent_execution`、`model_invocation`、`tool_execution` 和 stream checkpoint 继续负责 fencing、幂等和恢复；REST/SSE/前端只消费中心消息投影，审计日志在事务提交后输出经安全脱敏的完整节点详情。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis-Plus、MapStruct、Jackson 3（`tools.jackson`）、Flyway、PostgreSQL 17 JSONB、JUnit 5、Testcontainers、React 19、TypeScript、Vitest。

## Global Constraints

- 新功能只修改 `data-agent-v2/`；不得修改已废弃的 `data-agent/` 或把产品代码放到根仓库。
- 直接使用当前工作区，不创建、切换或使用 Git worktree，不派发 subagent。
- 当前工作树已有 SSE、契约和前端未提交改动；不得覆盖、回滚或夹带这些改动。修改重叠文件后必须使用 `git add -p`，并在每次提交前执行 `git diff --cached --check` 与 `git diff --cached`。
- 每个任务按 TDD 顺序执行：先新增会失败的最小测试，确认失败原因正确，再实现并验证，最后单独提交。
- 每个计划任务完成后创建一个 commit；除本任务明确列出的文件和 hunk 外不得暂存其他内容。
- 新增字段必须写中文字段注释；新增代码按逻辑块写中文注释；除配置 key、默认值和 error message 外，稳定字符串均定义为静态常量或 enum 值。
- `content text not null` 保存真实消息正文；运行中的 assistant 占位消息使用空字符串，不新增独立 internal assistant/tool 消息。
- Java 侧过程类型固定为有序 `List<ThinkingProcessItem>`，节点类型首版固定为 `THINKING`、`TOOL`，`schemaVersion` 固定为 `1`。
- 每个 ProcessItem 使用 UUID，记录 `createdAt`、`startedAt`、`completedAt`、`durationMillis` 和 `PREPARED/RUNNING/SUCCEEDED/FAILED` 状态；thinking 不使用 `PREPARED`。
- 单节点默认上限固定为 thinking `16384` UTF-8 bytes、tool input `32768` bytes、tool output `65536` bytes；整条 `thinkingProcess` 不设置大小上限。
- 超限节点在数据库保存合法的有界预览、`truncated`、原始安全载荷 UTF-8 字节数和 SHA-256；运行时恢复不得从日志补回完整载荷。
- 所有终态节点无论数据库是否截断，都必须在事务提交后向 `agent.conversation.process.audit` 输出经安全脱敏的完整结构化详情。
- 审计实现旁必须原样保留注释：`按主管审计要求记录经安全脱敏后的完整 ProcessItem 详情和工具调用结果，便于后续审计排查；不得根据数据库是否截断跳过该日志。`
- “完整”仅指安全脱敏后的完整业务载荷；密码、token、Authorization、Cookie、连接串、JDBC URL 和服务端执行上下文不得进入消息或日志。
- Flyway 使用执行时确认的下一个可用版本；当前仓库最新为 V12，因此本计划使用 V13 扩展/回填、V14 收紧/清理。若执行前出现更高版本，保持二者相邻并顺延到接下来的两个空闲版本，不修改既有迁移。

---

## 文件结构与职责

- `backend/agent-domain/.../ThinkingProcessItem.java`：定义多态过程节点的公共只读契约。
- `backend/agent-domain/.../ModelThinkingProcessItem.java`：定义 thinking 节点及其内容完整性元数据。
- `backend/agent-domain/.../ToolThinkingProcessItem.java`：定义单次工具调用从 prepared 到 terminal 的完整节点。
- `backend/agent-domain/.../BoundedProcessPayload.java`：表达完整结构化 JSON 或超限后的合法预览文档。
- `backend/agent-persistence/.../ConversationProcessDocumentCodec.java`：唯一负责强类型列表与 JSONB 文档互转并校验类型、版本、UUID、序号和状态。
- `backend/agent-persistence/.../ConversationMessageService.java`：负责中心消息读取、行锁、乐观更新和会话上下文范围查询。
- `backend/agent-web/.../process/ConversationProcessCoordinator.java`：在 owner/attempt fencing 下追加、推进和终结 ProcessItem。
- `backend/agent-web/.../process/ProcessPayloadSanitizer.java`：在持久化、计算大小、哈希和审计前递归脱敏。
- `backend/agent-web/.../process/ProcessPayloadLimiter.java`：按三项明确阈值产生有界载荷。
- `backend/agent-web/.../process/ThinkingProcessAuditLogger.java`：事务提交后向专用 logger 输出一次完整安全事件。
- `backend/agent-web/.../process/ConversationMessageChanged.java`：事务提交后触发本机 SSE 消息替换事件。
- `frontend/src/components/MessageProcessTimeline.tsx`：按 assistant 消息渲染可展开的 thinking/tool 时间线。
- `frontend/src/App.tsx`：根据处理中会话集合和当前路由按需建立、切换及关闭 activity/detail SSE。

### Task 1: 定义强类型 ProcessItem 并完成 JSONB 编解码

**Files:**
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ThinkingProcessType.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ThinkingProcessStatus.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ThinkingProcessItem.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ModelThinkingProcessItem.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ToolThinkingProcessItem.java`
- Create: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/BoundedProcessPayload.java`
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java`
- Modify: `backend/agent-persistence/pom.xml`
- Create: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/json/ConversationProcessDocumentCodec.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationMessageEntity.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapper.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
- Create: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/json/ConversationProcessDocumentCodecTest.java`
- Modify: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapperTest.java`

**Interfaces:**
- Produces: `sealed interface ThinkingProcessItem` with common accessors; records `ModelThinkingProcessItem` and `ToolThinkingProcessItem`; `ConversationProcessDocumentCodec.write(List<ThinkingProcessItem>): String`; `ConversationProcessDocumentCodec.read(String): List<ThinkingProcessItem>`.
- Produces: `ConversationMessage.getThinkingProcess()` always returns a non-null ordered list; factories initialize it with `List.of()`.

- [ ] **Step 1: Write failing codec and invariant tests**

```java
@Test
void roundTripsOrderedPolymorphicProcessItems() {
    UUID thinkingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Instant start = Instant.parse("2026-08-04T01:00:00Z");
    List<ThinkingProcessItem> items = List.of(
            new ModelThinkingProcessItem(thinkingId, 1L, ThinkingProcessStatus.SUCCEEDED,
                    start, start, start.plusMillis(7), 7L, 1,
                    "分析问题", false, 12L, "a".repeat(64), 1L, "codex", "gpt-5"),
            new ToolThinkingProcessItem(toolId, 2L, ThinkingProcessStatus.SUCCEEDED,
                    start, start.plusMillis(8), start.plusMillis(18), 10L, 1,
                    "current_time", "Current time", "tool_execution_1", "call_1", 1L,
                    new BoundedProcessPayload(Map.of("zone", "Asia/Shanghai"), null, false,
                            24L, "b".repeat(64), "application/json"),
                    new BoundedProcessPayload(Map.of("time", "09:00"), null, false,
                            17L, "c".repeat(64), "application/json"),
                    true, null, null));

    assertThat(codec.read(codec.write(items))).containsExactlyElementsOf(items);
}

@ParameterizedTest
@ValueSource(strings = {
    "[{\"type\":\"UNKNOWN\",\"schemaVersion\":1}]",
    "[{\"type\":\"THINKING\",\"schemaVersion\":2}]"
})
void rejectsUnknownTypeOrSchemaVersion(String document) {
    assertThatThrownBy(() -> codec.read(document))
            .isInstanceOf(IllegalStateException.class);
}
```

另外增加重复 `processItemId`、重复/非递增 `sequence`、thinking 使用 `PREPARED`、终态缺少完成时间、运行态携带 `durationMillis` 的失败断言。

- [ ] **Step 2: Run the focused persistence tests and confirm the intended failure**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  -Dtest=ConversationProcessDocumentCodecTest,ConversationPersistenceMapperTest test
```

Expected: FAIL because the ProcessItem types and codec do not exist.

- [ ] **Step 3: Implement the domain contract and codec**

Use this exact public shape; keep JSON field names identical to record component names:

```java
public sealed interface ThinkingProcessItem
        permits ModelThinkingProcessItem, ToolThinkingProcessItem {
    UUID processItemId();
    long sequence();
    ThinkingProcessType type();
    ThinkingProcessStatus status();
    Instant createdAt();
    Instant startedAt();
    Instant completedAt();
    Long durationMillis();
    int schemaVersion();
}

public record BoundedProcessPayload(
        Object value,
        String preview,
        boolean truncated,
        long originalSizeBytes,
        String sha256,
        String contentType) {}
```

`ModelThinkingProcessItem.type()` 固定返回 `THINKING`，`ToolThinkingProcessItem.type()` 固定返回 `TOOL`，不把 `type` 作为可伪造的构造参数。codec 使用 `tools.jackson.databind.ObjectMapper` 先读为树，再按 `type` 显式构造具体 record；不得启用“未知类型回退为空列表”。给 `agent-persistence` 增加现有版本管理下的 `spring-boot-starter-jackson` 依赖。

Entity 仍以字符串承接 JDBC JSONB，领域模型保持强类型 List：

```java
@TableField(jdbcType = JdbcType.OTHER)
private String thinkingProcess;
```

在 MapStruct 的 message 双向映射上忽略 `thinkingProcess`，由 `ConversationMessageService` 唯一调用 codec 写入/读取，避免 MapStruct 隐式字符串转换。`ConversationMessage` 的 setter 做 defensive copy，空值统一为 `List.of()`。

- [ ] **Step 4: Run the focused tests and inspect serialized JSON**

Run the Step 2 command again. Expected: PASS，且测试额外断言 JSON 根节点为数组、元素顺序为 `1, 2`、`type` 分别为 `THINKING/TOOL`。

- [ ] **Step 5: Commit only Task 1 hunks**

```powershell
git add backend/agent-domain backend/agent-persistence/pom.xml `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/json `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationMessageEntity.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapper.java `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence
git add -p backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java
git diff --cached --check
git diff --cached
git commit -m "feat: define typed conversation process items"
```

### Task 2: 扩展 JSONB 结构并把旧 internal 数据回填到中心消息

**Files:**
- Create: `backend/agent-persistence/src/main/resources/db/migration/V13__content_centered_conversation_messages.sql`
- Modify: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ReactExecutionMigrationTest.java`
- Modify: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ConversationExecutionSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 JSON 字段结构。
- Produces: `conversation_message.thinking_process jsonb not null default '[]'`; nullable `tool_execution.tool_process_item_id uuid` 完成历史回填。旧列暂时保留到 Task 7 的 V14，使 V13 成为可单独验证的扩展迁移。

- [ ] **Step 1: Add migration fixtures and failing assertions**

在 `ReactExecutionMigrationTest` 迁移到 V12 后插入以下三组历史数据再迁移到 latest：

```java
// A: 已完成执行：user + internal assistant(tool_calls) + internal tool observation + public answer
// B: 活动执行：user + internal assistant(tool_calls)，尚无 public answer
// C: 活动执行：只有 user，尚未调用工具
```

断言：A 的 public answer 聚合一个 terminal TOOL 节点；B 复用最早 internal assistant 为 `content=''` 的中心行；C 新建唯一空中心行；所有 user 行为 `[]`；`tool_execution.tool_process_item_id` 与对应 JSON UUID 相同。V13 后旧列、internal 辅助行和 observation 外键仍存在，供 V14 在新运行时代码同时落地时统一清理。

- [ ] **Step 2: Run migration tests and confirm schema assertions fail**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  -Dtest=ReactExecutionMigrationTest,ConversationExecutionSchemaMigrationTest test
```

Expected: FAIL because V13 and the new columns/constraints do not exist.

- [ ] **Step 3: Implement V13 as a data-preserving migration**

The migration must perform these concrete operations in order:

```sql
alter table conversation_message
    add column thinking_process jsonb not null default '[]'::jsonb,
    add constraint ck_conversation_message_thinking_process_array
        check (jsonb_typeof(thinking_process) = 'array');

alter table tool_execution add column tool_process_item_id uuid;

create temporary table migrated_message_center (
    tenant_id varchar(64) not null,
    execution_id varchar(64) not null,
    conversation_message_id varchar(64) not null,
    primary key (tenant_id, execution_id)
) on commit drop;

create temporary table migrated_tool_process_item (
    tenant_id varchar(64) not null,
    tool_execution_id varchar(64) not null,
    center_message_id varchar(64) not null,
    process_item_id uuid not null,
    process_sequence bigint not null,
    process_document jsonb not null,
    primary key (tenant_id, tool_execution_id)
) on commit drop;
```

填充中心表时按以下优先级且每个 execution 只取一行：公开 assistant 最后回答；否则最早 internal assistant；否则为该 execution 插入新 assistant 占位消息并先锁定、递增 `conversation_session.conversation_message_sequence`。迁移 ID 使用 `conversation_message_` 加去横线 UUID，长度保持在 64 内。

工具节点按 `(model_invocation_sequence, 原 assistant 消息序号, tool_execution.id)` 分配 `row_number()`；从匹配 `tool_call_id` 的 `tool_calls->'calls'` 取 `arguments`，从 observation `content::jsonb` 取结果，使用如下稳定 JSON 形状：

```sql
jsonb_build_object(
  'processItemId', process_item_id,
  'sequence', process_sequence,
  'type', 'TOOL',
  'status', case status when 'succeeded' then 'SUCCEEDED'
                        when 'failed' then 'FAILED'
                        when 'executing' then 'RUNNING'
                        else 'PREPARED' end,
  'createdAt', created_at,
  'startedAt', started_at,
  'completedAt', completed_at,
  'durationMillis', case when started_at is not null and completed_at is not null
                         then greatest(0, (extract(epoch from completed_at - started_at) * 1000)::bigint)
                         else null end,
  'schemaVersion', 1,
  'toolId', tool_name,
  'toolName', tool_name,
  'toolExecutionId', tool_execution_id,
  'toolCallId', tool_call_id,
  'modelInvocationSequence', model_invocation_sequence,
  'input', jsonb_build_object('value', arguments, 'preview', null, 'truncated', false,
      'originalSizeBytes', octet_length(convert_to(arguments::text, 'UTF8')),
      'sha256', encode(sha256(convert_to(arguments::text, 'UTF8')), 'hex'),
      'contentType', 'application/json'),
  'output', case when observation is null then null else jsonb_build_object(
      'value', observation, 'preview', null, 'truncated', false,
      'originalSizeBytes', octet_length(convert_to(observation::text, 'UTF8')),
      'sha256', encode(sha256(convert_to(observation::text, 'UTF8')), 'hex'),
      'contentType', 'application/json') end,
  'success', case when status = 'succeeded' then true when status = 'failed' then false else null end,
  'errorCode', failure_code,
  'errorMessage', null
)
```

用 `jsonb_agg(process_document order by process_sequence)` 更新中心行，然后更新 `tool_execution.assistant_conversation_message_id` 和 `tool_process_item_id`。V13 不删除旧列、旧约束或剩余 internal 行，也不把 `tool_process_item_id` 收紧为 NOT NULL；这些 contract 操作与停止旧写入的 Task 7/V14 同时提交。

在构造 `arguments` 和 `observation` 前创建一个仅在当前迁移事务存在的 `pg_temp.sanitize_process_json(jsonb)` 递归函数：对象 key 归一化后命中 Task 4 的敏感字段集合时把 value 替换为 JSON 字符串 `"[REDACTED]"`，数组逐元素递归，标量原样返回。大小与 `sha256` 只能针对该函数返回的安全 JSON 计算。迁移不得生成 THINKING 节点，也不得将数据库日志作为数据源。

- [ ] **Step 4: Run migration tests and inspect the migrated JSON/constraints**

Run the Step 2 command again. Expected: PASS. 另外在测试中使用 `information_schema.columns` 精确断言新列、JSONB 数组约束和历史 UUID 回填存在，并断言旧列仍存在以证明本步是 expand migration。

- [ ] **Step 5: Commit the migration and its tests**

```powershell
git add backend/agent-persistence/src/main/resources/db/migration/V13__content_centered_conversation_messages.sql `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ReactExecutionMigrationTest.java `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ConversationExecutionSchemaMigrationTest.java
git diff --cached --check
git diff --cached
git commit -m "feat: migrate conversations to content centered storage"
```

### Task 3: 创建并锁定每次执行唯一的 assistant 中心消息

**Files:**
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/mapper/ConversationMessageMapper.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionTransactionService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/AgentExecutionSubmissionIntegrationTest.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java`

**Interfaces:**
- Produces: `ConversationMessage.assistantPlaceholder(...)`; `ConversationMessageService.lockAssistantByExecutionId(String)`; `findByConversationSessionIdUpTo(String,long)`; `findByExecutionId(String)` 返回真实 user/assistant 行。
- Guarantees: message submission and retry each create exactly one assistant placeholder in the same transaction as the execution.

- [ ] **Step 1: Write failing submission, retry, and lock tests**

```java
@Test
void submissionPersistsOneUserAndOneEmptyAssistantMessage() {
    submit("解释今天的指标异常");
    List<MessageRow> rows = messageRowsForExecution(executionId());
    assertThat(rows).extracting(MessageRow::role, MessageRow::content)
            .containsExactly(tuple("user", "解释今天的指标异常"), tuple("assistant", ""));
    assertThat(rows).allSatisfy(row -> assertThat(row.thinkingProcess()).isEqualTo("[]"));
}

@Test
void retryCreatesExactlyOneNewAssistantPlaceholderWithoutDuplicatingUserMessage() {
    retryTerminalExecution();
    assertThat(assistantRowsForExecution(retryExecutionId())).hasSize(1);
    assertThat(userRowsForSession()).hasSize(1);
}
```

- [ ] **Step 2: Run focused integration tests and verify they fail on the missing placeholder**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=AgentExecutionSubmissionIntegrationTest,ContentCenteredMessageIntegrationTest test
```

Expected: FAIL because submission/retry currently create no assistant placeholder.

- [ ] **Step 3: Implement placeholder creation and real-message queries**

Change submission allocation to reserve two adjacent message sequences and one event sequence. If `ConversationSession.AllocatedSequences` currently represents only one message, add a dedicated `allocateSubmissionSequences(int messageCount)` or a two-message result rather than calling the allocator twice outside the session lock.

Use this factory and remove internal-only factories/fields from the domain model:

```java
public static ConversationMessage assistantPlaceholder(
        String messageId, String sessionId, String executionId, long sequence, String role) {
    ConversationMessage message = base(messageId, sessionId, role, "", sequence);
    message.executionId = executionId;
    message.thinkingProcess = List.of();
    return message;
}
```

Add an explicit row-lock mapper query:

```java
@Select("""
        select * from conversation_message
         where execution_id = #{executionId}
           and role = 'assistant' and is_deleted = false
         for update
        """)
ConversationMessageEntity selectAssistantByExecutionIdForUpdate(
        @Param("executionId") String executionId);
```

Submission saves `user -> execution -> associate user -> assistant placeholder -> event/checkpoint`. Retry locks the session, allocates one new message sequence plus the event sequence, then saves its placeholder before returning. Idempotent replay must return existing rows and never allocate a second placeholder.

- [ ] **Step 4: Run the focused integration tests**

Run Step 2 again. Expected: PASS, including the unique assistant index under duplicate/replayed requests.

- [ ] **Step 5: Commit Task 3 hunks**

Use `git add -p` for the already-dirty service/control files, inspect the staged diff, then commit:

```powershell
git add backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/mapper/ConversationMessageMapper.java `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/AgentExecutionSubmissionIntegrationTest.java `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/ContentCenteredMessageIntegrationTest.java
git add -p backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionTransactionService.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java
git diff --cached --check
git diff --cached
git commit -m "feat: create assistant message placeholders"
```

### Task 4: 安全脱敏、单节点截断与提交后完整审计

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationProcessProperties.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ProcessPayloadSanitizer.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ProcessPayloadLimiter.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ProcessAuditEvent.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ThinkingProcessAuditLogger.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/process/ProcessPayloadPolicyTest.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/process/ThinkingProcessAuditLoggerTest.java`

**Interfaces:**
- Produces: `sanitizeJson(JsonNode): JsonNode`; `sanitizeText(String): String`; `boundJson(JsonNode,int): BoundedProcessPayload`; `boundText(String,int): ProcessPayloadLimiter.BoundedTextResult`; `ProcessAuditEvent` carries terminal DB item plus full safe thinking/input/output.

- [ ] **Step 1: Write boundary, redaction, hash, and audit failure tests**

Test exact sizes `limit-1`, `limit`, and `limit+1` using multibyte Chinese text; verify preview ends only at a valid UTF-8 boundary, `originalSizeBytes` and SHA-256 refer to the full sanitized value, and tool preview is a valid JSON object with `value=null` rather than a chopped JSON string.

```java
@Test
void auditUsesSameUuidAndFullSafePayloadEvenWhenDatabasePayloadIsTruncated() {
    ProcessAuditEvent event = truncatedToolEventWithSecret("token", "secret-value");
    logger.onCommitted(event);
    String json = capturedAuditJson();
    assertThat(json).contains(event.item().processItemId().toString());
    assertThat(json).contains("tail-beyond-database-preview");
    assertThat(json).doesNotContain("secret-value").contains("[REDACTED]");
}

@Test
void auditSinkFailureDoesNotEscapeAfterCommitListener() {
    assertThatCode(() -> failingLogger.onCommitted(event())).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run tests and confirm missing policy components**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  -Dtest=ProcessPayloadPolicyTest,ThinkingProcessAuditLoggerTest test
```

Expected: FAIL because the sanitizer, limiter, and audit listener do not exist.

- [ ] **Step 3: Implement exact security and capacity policy**

Bind these keys and defaults in `ConversationProcessProperties`:

```java
@ConfigurationProperties("data-agent.conversation.process")
public record ConversationProcessProperties(
        @DefaultValue("16384") int maxThinkingBytes,
        @DefaultValue("32768") int maxToolInputBytes,
        @DefaultValue("65536") int maxToolOutputBytes) {}
```

The JSON sanitizer recursively replaces values whose normalized key matches `password`, `passwd`, `secret`, `token`, `access_token`, `accessToken`, `refresh_token`, `refreshToken`, `authorization`, `cookie`, `set-cookie`, `connection_string`, `connectionString`, `jdbc_url`, or `jdbcUrl`. The text sanitizer applies a case-insensitive key/value pattern to the same names. Centralize the replacement string as `REDACTED_VALUE = "[REDACTED]"`.

The limiter canonicalizes sanitized JSON once, computes UTF-8 bytes and SHA-256, and either stores the structured value or a UTF-8-safe preview. It must not count or cap the outer list.

Define the text result as a nested public record so later tasks use one exact type:

```java
public record BoundedTextResult(
        String content,
        boolean truncated,
        long originalContentSizeBytes,
        String contentSha256) {}
```

Use this exact committed-event shape; every non-applicable full field is `null`, and all non-null full fields are already sanitized before construction:

```java
public record ProcessAuditEvent(
        ThinkingProcessItem item,
        String fullThinkingContent,
        JsonNode fullToolInput,
        JsonNode fullToolOutput) {
    public Map<String, Object> safeFullDocument(ObjectMapper json) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("processItem", item);
        document.put("fullThinkingContent", fullThinkingContent);
        document.put("fullToolInput", fullToolInput);
        document.put("fullToolOutput", fullToolOutput);
        return document;
    }
}
```

The audit listener uses `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`, catches logger serialization/runtime failures, and writes a separate warning without throwing. Put this exact comment directly above the audit `AUDIT.info(...)` statement:

```java
// 按主管审计要求记录经安全脱敏后的完整 ProcessItem 详情和工具调用结果，便于后续审计排查；不得根据数据库是否截断跳过该日志。
AUDIT.info("{}", json.writeValueAsString(event.safeFullDocument(json)));
```

Use `LoggerFactory.getLogger("agent.conversation.process.audit")`; do not log raw exceptions, model context, request headers, environment variables, or credentials.

- [ ] **Step 4: Run policy tests**

Run Step 2 again. Expected: PASS for untruncated and truncated nodes, recursive arrays/objects, text secrets, UUID correlation, and audit sink failure isolation.

- [ ] **Step 5: Commit Task 4**

```powershell
git add backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process `
  backend/agent-web/src/test/java/com/datagent/agentv2/conversation/process
git diff --cached --check
git diff --cached
git commit -m "feat: secure and audit conversation process payloads"
```

### Task 5: 建立带 fencing 的中心消息 ProcessItem 协调器

**Files:**
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationProcessCoordinator.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationMessageChanged.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/process/ConversationProcessCoordinatorIntegrationTest.java`

**Interfaces:**
- Produces: `startThinking(run, invocationSequence, provider, model, delta, now): UUID`; `appendThinking(run,itemId,delta,now)`; `finishThinking(run,itemId,status,now)`; `prepareTool(run,invocationSequence,ModelToolCall,toolExecutionId,now): UUID`; `startTool(run,itemId,now)`; `finishTool(run,itemId,ToolExecutionResult,now)`; `failOpenItems(executionId,errorCode,errorMessage,now)`.
- Guarantees: every mutating call locks/fences execution then locks the assistant row, validates UUID/state transition, updates the message version once, and publishes `ConversationMessageChanged` only after the save succeeds.

- [ ] **Step 1: Write failing transition and stale-owner integration tests**

```java
@Test
void processTransitionsKeepUuidSequenceAndDurationOnOneMessage() {
    UUID id = coordinator.startThinking(run, 1L, "codex", "gpt-5", "分析", t0);
    coordinator.appendThinking(run, id, "完成", t0.plusMillis(4));
    coordinator.finishThinking(run, id, ThinkingProcessStatus.SUCCEEDED, t0.plusMillis(9));
    ConversationMessage assistant = messages.findAssistantByExecutionId(run.executionId());
    assertThat(assistant.getThinkingProcess()).singleElement().satisfies(item -> {
        assertThat(item.processItemId()).isEqualTo(id);
        assertThat(item.sequence()).isEqualTo(1L);
        assertThat(item.durationMillis()).isEqualTo(9L);
    });
}

@Test
void staleAttemptCannotAppendOrReplaceAProcessItem() {
    assertThatThrownBy(() -> coordinator.appendThinking(staleRun, itemId, "late", now))
            .isInstanceOf(ExecutionOwnershipLostException.class);
}
```

Also assert duplicate UUID/sequence and illegal transitions (`SUCCEEDED -> RUNNING`, `PREPARED thinking`) roll back without changing JSONB.

- [ ] **Step 2: Run the coordinator integration test and verify failure**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=ConversationProcessCoordinatorIntegrationTest test
```

Expected: FAIL because the coordinator and message transition helpers do not exist.

- [ ] **Step 3: Implement the coordinator with one mutation path**

Use one private method to prevent inconsistent locking:

```java
private <T> T mutate(
        ClaimedAgentExecution run,
        Function<ConversationMessage, Mutation<T>> mutation) {
    AgentExecution owned = requireOwnedLocked(run);
    ConversationMessage assistant = messages.lockAssistantByExecutionId(owned.getExecutionId());
    Mutation<T> changed = mutation.apply(assistant);
    if (!messages.updateById(assistant)) {
        throw new ExecutionOwnershipLostException(run.executionId());
    }
    applicationEvents.publishEvent(new ConversationMessageChanged(
            run.tenantId(), assistant.getConversationSessionId(),
            assistant.getConversationMessageId(), assistant.getVersion()));
    changed.auditEvents().forEach(applicationEvents::publishEvent);
    return changed.result();
}

private record Mutation<T>(T result, List<ProcessAuditEvent> auditEvents) {}
```

The message notification has one stable public record shape:

```java
public record ConversationMessageChanged(
        String tenantId,
        String conversationSessionId,
        String conversationMessageId,
        long messageVersion) {}
```

`requireOwnedLocked` compares status `running`, owner instance, attempt and deadline. Node sequence is `max(existing.sequence)+1`; no total-size calculation exists. Thinking append keeps an in-memory full safe buffer keyed by `(executionId, attempt, processItemId)` but writes bounded content at the checkpoint cadence; terminal mutation removes the buffer only after persistence succeeds. Tool full input/output travels through the method call and audit event, never through an unsanitized log.

- [ ] **Step 4: Run coordinator and policy tests**

Run Step 2 and Task 4’s test command. Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```powershell
git add backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/process
git diff --cached --check
git diff --cached
git commit -m "feat: coordinate persisted conversation process items"
```

### Task 6: 恢复模型 thinking delta 并更新同一中心消息

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/ModelOutputDelta.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/AgentModelGateway.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/OllamaAgentModelGateway.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/CodexAgentModelGateway.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/CodexAppServerClient.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model/OllamaAgentModelGatewayTest.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model/CodexAppServerClientTest.java`
- Create: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/react/ModelThinkingProcessTest.java`

**Interfaces:**
- Produces: `record ModelOutputDelta(Kind kind,String content,String modelProvider,String modelName)` with `Kind.THINKING` and `Kind.ANSWER`; gateway callback changes to `Consumer<ModelOutputDelta>`.
- Consumes: Task 5 thinking coordinator methods.

- [ ] **Step 1: Write provider and lifecycle tests**

For Ollama assert `.enableThinking()` is present, `message.thinking()` emits `THINKING`, and `message.content()` emits `ANSWER` in provider order. For Codex feed notifications for `item/reasoning/textDelta`, `item/reasoning/summaryTextDelta`, and `item/agentMessage/delta`; assert raw reasoning wins over summary for the same item and no content is duplicated. For the coordinator assert first thinking delta creates one RUNNING item, later deltas append to it, tool/final-answer boundary finishes it, and a later model invocation creates a new UUID.

- [ ] **Step 2: Run tests and verify the current answer-only callback fails**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  -Dtest=OllamaAgentModelGatewayTest,CodexAppServerClientTest,ModelThinkingProcessTest test
```

Expected: FAIL because `AgentModelGateway` still consumes `String` answer deltas and both providers suppress thinking.

- [ ] **Step 3: Implement typed model deltas and thinking boundaries**

```java
public record ModelOutputDelta(
        Kind kind, String content, String modelProvider, String modelName) {
    public enum Kind { THINKING, ANSWER }
    public static ModelOutputDelta thinking(String value, String provider, String model) {
        return new ModelOutputDelta(Kind.THINKING, value, provider, model);
    }
    public static ModelOutputDelta answer(String value, String provider, String model) {
        return new ModelOutputDelta(Kind.ANSWER, value, provider, model);
    }
}
```

Ollama uses the previously supported `enableThinking()` path and emits provider/model constants `ollama` plus `OllamaModelProperties.getModel()`. Codex emits `codex` plus `CodexModelProperties.getModel()`; the metadata reaches `ConversationProcessCoordinator.startThinking(...)` on the first thinking delta. Codex recognizes these exact method names as constants:

```java
private static final String NOTIFICATION_REASONING_TEXT_DELTA = "item/reasoning/textDelta";
private static final String NOTIFICATION_REASONING_SUMMARY_TEXT_DELTA =
        "item/reasoning/summaryTextDelta";
private static final String NOTIFICATION_AGENT_MESSAGE_DELTA = "item/agentMessage/delta";
```

Buffer summary deltas per reasoning item until that item completes. If any raw `textDelta` arrives for the item, discard the buffered summary and stream only raw text; if the item completes without raw text, emit the buffered summary once. `ModelInvocationCoordinator.checkpoint` switches on kind: ANSWER continues through `ExecutionStreamCheckpointCoordinator`; THINKING goes through `ConversationProcessCoordinator` and its 512-character/250-ms checkpoint window. Before archiving tools, before accepting the first answer delta, and on provider failure, close the current thinking item exactly once.

- [ ] **Step 4: Run the provider/lifecycle tests**

Run Step 2 again. Expected: PASS; existing answer streaming assertions remain unchanged.

- [ ] **Step 5: Commit Task 6**

```powershell
git add backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react `
  backend/agent-web/src/test/java/com/datagent/agentv2/conversation/model `
  backend/agent-web/src/test/java/com/datagent/agentv2/conversation/react
git diff --cached --check
git diff --cached
git commit -m "feat: persist model thinking process"
```

### Task 7: 用单个 Tool ProcessItem 替代 assistant call 与 observation 消息

**Files:**
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationMessageEntity.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ToolExecutionEntity.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/mapper/ToolExecutionMapper.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ToolExecutionService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolExecutionTransactionService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolExecutionCoordinator.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/MessageRole.java`
- Delete: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/MessageVisibility.java`
- Create: `backend/agent-persistence/src/main/resources/db/migration/V14__remove_internal_conversation_message_storage.sql`
- Modify: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ReactExecutionMigrationTest.java`
- Modify: `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ConversationExecutionSchemaMigrationTest.java`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/tool/ToolExecutionCoordinatorTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactCrashRecoveryIntegrationTest.java`

**Interfaces:**
- `ToolExecutionEntity.toolProcessItemId: UUID` replaces observation-message identity.
- `archiveToolCalls` atomically appends PREPARED nodes and saves prepared tool rows; `beginInvocation` atomically starts both; `persistResult` atomically terminalizes both.

- [ ] **Step 1: Replace observation-count assertions with one-message node assertions**

```java
assertThat(messagesForExecution()).extracting(ConversationMessage::getRole)
        .containsExactly("user", "assistant");
assertThat(assistant().getThinkingProcess())
        .filteredOn(ToolThinkingProcessItem.class::isInstance)
        .hasSize(6);
assertThat(toolExecutions()).allSatisfy(execution ->
        assertThat(toolItem(execution.getToolProcessItemId()).toolExecutionId())
                .isEqualTo(execution.getToolExecutionId()));
```

Add success, declared tool failure, timeout, unknown tool, and crash-after-terminal-commit cases. Assert prepared wait time is excluded from duration and a never-started failed node keeps `startedAt/durationMillis == null`. Extend the migration tests to assert V14 removes every internal row/column/foreign key, makes `tool_process_item_id` NOT NULL, restricts roles to `user/assistant/system`, and creates the partial unique assistant index.

- [ ] **Step 2: Run tool/recovery tests and verify legacy message assertions fail**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=ToolExecutionCoordinatorTest,ReactCrashRecoveryIntegrationTest test
```

Expected: FAIL because the implementation still creates internal assistant/tool observation rows.

- [ ] **Step 3: Implement atomic tool node lifecycle**

`ModelInvocationCoordinator.archiveToolCalls` locks the owned execution and center message, generates one UUID per call, sanitizes/bounds arguments, appends PREPARED nodes in provider order, and creates `ToolExecutionEntity` rows pointing to the center message and UUID. Remove `toolCallsJson` and `assistantMessage(...internal...)`。从领域模型和 Entity 删除 `toolCalls/toolExecutionId/toolCallId/toolName/visibility`，把会话查询改成无需 visibility 过滤的真实消息查询；同时从 `MessageRole` 删除 `TOOL`，删除不再被引用的 `MessageVisibility`。

V14 与停止旧写入的代码一起完成 contract migration：删除 observation/assistant 旧外键和 shape constraints，删除除中心行外的 `visibility='internal'` 消息，删除 `tool_calls`、`tool_execution_id`、`tool_call_id`、`tool_name`、`visibility`、`observation_conversation_message_id`，随后执行：

```sql
alter table conversation_message
    add constraint ck_conversation_message_role
        check (role in ('user', 'assistant', 'system'));

create unique index uq_conversation_message_execution_assistant
    on conversation_message (tenant_id, execution_id)
    where role = 'assistant' and is_deleted = false;

alter table tool_execution
    alter column tool_process_item_id set not null;
```

`ToolExecutionTransactionService.beginInvocation` reads arguments from the Tool ProcessItem, resolves the registry tool, then in the same `REQUIRES_NEW` transaction advances both `tool_execution.status='executing'` and the node to RUNNING using one `Instant now`.

Rename `persistObservation` to:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public ProcessAuditEvent persistResult(
        ClaimedAgentExecution run,
        ToolExecutionEntity candidate,
        ToolExecutionResult result,
        Instant completedAt)
```

It sanitizes and bounds the full result, updates the original node, then terminalizes `tool_execution`; either failure rolls back both. The caller publishes the returned audit event after the method returns; the event listener itself runs AFTER_COMMIT. Delete all creation of `ConversationMessage.observation` and all reads of `observationConversationMessageId`.

- [ ] **Step 4: Run tool/recovery tests**

Run Step 2 again. Expected: PASS, exactly two real messages for a new execution, no duplicate tool UUID after recovery, and one audit event per terminal tool.

- [ ] **Step 5: Commit Task 7**

```powershell
git add backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationMessageEntity.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ToolExecutionEntity.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/mapper/ToolExecutionMapper.java `
  backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ToolExecutionService.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/MessageRole.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/MessageVisibility.java `
  backend/agent-persistence/src/main/resources/db/migration/V14__remove_internal_conversation_message_storage.sql `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ReactExecutionMigrationTest.java `
  backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ConversationExecutionSchemaMigrationTest.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool `
  backend/agent-web/src/test/java/com/datagent/agentv2/conversation/tool `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactCrashRecoveryIntegrationTest.java
git add -p backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java
git diff --cached --check
git diff --cached
git commit -m "feat: bind tool execution to assistant process items"
```

### Task 8: 从真实消息和 Tool ProcessItem 重建上下文与恢复计划

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ConversationContextLoader.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactRecoveryPlanner.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ConversationContextLoaderIntegrationTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ReactCrashRecoveryIntegrationTest.java`

**Interfaces:**
- Consumes: `ConversationMessageService.findByConversationSessionIdUpTo(...)`, ordered Tool items, and `tool_execution.toolProcessItemId`.
- Guarantees: thinking never enters model input; completed tools are never re-executed; truncated database payload is the only recovery source.

- [ ] **Step 1: Write failing context and recovery cases**

Create a session with historical user/assistant content, current user/assistant placeholder, two Tool items from one model invocation, and one Thinking item. Assert exact model message order:

```text
USER(history question)
ASSISTANT(history answer)
USER(current question)
ASSISTANT(tool calls in ProcessItem sequence)
TOOL(result 1)
TOOL(result 2)
```

Assert no thinking text appears. For truncated output assert the model receives the stored preview document and the completed tool remains terminal without another invocation.

- [ ] **Step 2: Run context/recovery tests and verify they fail on legacy tool messages**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=ConversationContextLoaderIntegrationTest,ReactCrashRecoveryIntegrationTest test
```

Expected: FAIL because the loader parses `tool_calls` and observation rows.

- [ ] **Step 3: Implement deterministic reconstruction**

Resolve the execution to its `conversationSessionId`, query all real messages up to the invocation boundary, then for each assistant row group TOOL items by `modelInvocationSequence` and order by `sequence`. Convert their bounded `input.value` or preview document to `ModelToolCall`; emit tool results from bounded `output`. Append non-empty assistant `content` after its tool groups. Skip every THINKING item unconditionally.

`ReactRecoveryPlanner.validateTerminalObservation` becomes `validateTerminalProcessItem`: find the center message, locate the exact UUID, verify it is TOOL, has matching execution/call/name, and is terminal. `PREPARED/RUNNING` uses the existing idempotency/recovery rule without allocating a new UUID.

- [ ] **Step 4: Run context/recovery tests**

Run Step 2 again. Expected: PASS with exact order and zero thinking leakage.

- [ ] **Step 5: Commit Task 8**

```powershell
git add backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ConversationContextLoader.java `
  backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactRecoveryPlanner.java `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react
git diff --cached --check
git diff --cached
git commit -m "feat: rebuild model context from message process items"
```

### Task 9: 在完成、失败、取消和接管边界收敛 ProcessItem

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ReactExecutionAdapter.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionLeaseService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java`
- Create: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationIntegrationTest.java`

**Interfaces:**
- Produces: `cancelOwned(run, now)` and `failOwned(run, failureCode, safeMessage, now)` atomically terminalize execution/attempt and open nodes; `closeExpiredLocked(execution,...)` handles budget/recovery exhaustion.
- Guarantees: final answer updates the existing assistant row; no terminal execution retains PREPARED/RUNNING nodes.

- [ ] **Step 1: Write failure/cancel/final-answer tests**

Assert final completion writes checkpointed answer into the pre-existing assistant ID and does not insert a third message. Assert user cancellation, model failure, deadline failure, recovery exhaustion, and unknown tool failure close every open node. A never-started tool retains null duration; a running item uses the same terminal `Instant` for completion and duration.

- [ ] **Step 2: Run termination tests and verify open nodes remain**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=AgentExecutionProcessTerminationIntegrationTest,AgentExecutionLeaseRecoveryIntegrationTest test
```

Expected: FAIL because current terminal paths update execution state without closing process nodes and final answer inserts a new message.

- [ ] **Step 3: Implement atomic termination and final content update**

`completeFinalAnswer` must flush the answer checkpoint, lock the owned execution and assistant center, close an active thinking item, set `assistant.content` to the authoritative full answer, update the same row, and only then complete model invocation/execution/attempt/event in the same transaction. Remove `messages.save(assistantMessage(...))`.

`AgentExecutionProcessTerminationService` owns the transaction that closes nodes and updates execution state. `ReactExecutionAdapter` catches non-ownership runtime failures and calls `failOwned`; `cancelIfRequested`, direct cancellation, deadline failure, expired-budget failure, and recovery exhaustion delegate to this service or invoke its locked helper before committing their terminal state. An `ExecutionOwnershipLostException` caused by takeover must not let the stale worker mutate nodes.

- [ ] **Step 4: Run termination and submission tests**

Run Step 2 plus Task 3’s test command. Expected: PASS; terminal executions have zero open nodes and exactly one assistant row.

- [ ] **Step 5: Commit Task 9**

Use partial staging for dirty control files, inspect the staged diff, then commit:

```powershell
git add backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationService.java `
  backend/agent-server/src/test/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationIntegrationTest.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ReactExecutionAdapter.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionLeaseService.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java
git diff --cached --check
git diff --cached
git commit -m "feat: converge process items at execution boundaries"
```

### Task 10: 将 thinkingProcess 暴露到 REST/SSE 并实时替换消息

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationView.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationApplicationService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ConversationStreamEventType.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java`
- Modify: `contracts/openapi.yaml`
- Modify: `contracts/schemas/session-sse-event.schema.json`
- Create: `contracts/examples/sse/message-update.json`
- Modify: `contracts/examples/sse/session-snapshot.json`
- Modify: `contracts/examples/sse/execution-update.json`
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractExamplesTest.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/stream/ConversationSseIntegrationTest.java`

**Interfaces:**
- `ConversationView.MessageView` adds `long version` and `List<ThinkingProcessItem> thinkingProcess`.
- Adds SSE event `message_update` with `{type, conversationSessionId, message, occurredAt}`.

- [ ] **Step 1: Write contract and SSE replacement tests**

Assert REST, `session_snapshot`, `execution_update`, and `message_update` use the same Message schema; user messages return `thinkingProcess: []`; assistant update includes the same message ID, incremented version, ordered nodes, timestamps, durations, truncation metadata, and final content.

Cross-instance integration test opens SSE on instance A, mutates the process on instance B, runs the existing reconciliation tick, and expects one `message_update`. Repeating the same version must not send another replacement. Reconnect must receive the PostgreSQL snapshot even if the low-latency update was missed.

- [ ] **Step 2: Run contract/SSE tests and verify the schema mismatch**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  -Dtest='com.datagent.agentv2.contracts.*Test' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest=ConversationSseIntegrationTest test
```

Expected: FAIL because MessageView and the SSE union do not contain process data/message updates.

- [ ] **Step 3: Implement one authoritative message projection**

`ConversationApplicationService.messageView` passes an immutable process list and message version. Add `messageViewById` and a batch reconciliation projection that returns the active assistant message only when its version exceeds the subscription’s remembered version.

`ConversationEventStreamFacade` listens for `ConversationMessageChanged` AFTER_COMMIT and immediately sends `message_update` to local subscribers. Its one-second reconciliation also compares `(conversationMessageId, version)` so another instance converges without Redis. `message_update` is a full replacement, not a patch; frontend idempotency therefore depends on message ID/version and ProcessItem UUID. Initial/reconnect `session_snapshot` remains authoritative.

Update OpenAPI and JSON Schema with discriminated THINKING/TOOL shapes, required common timestamp fields, nullable lifecycle fields, and the bounded payload object. Keep `thinkingProcess` required and allow an empty array.

- [ ] **Step 4: Run contract/SSE tests**

Run Step 2 again. Expected: PASS, including cross-instance reconciliation and reconnect.

- [ ] **Step 5: Commit only Task 10 hunks**

Every listed source/contract file is already dirty or adjacent to dirty work. Use `git add -p` for all modified files, add only the new example directly, inspect staged changes, then commit:

```powershell
git add contracts/examples/sse/message-update.json
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationView.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationApplicationService.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ConversationStreamEventType.java
git add -p backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java
git add -p contracts/openapi.yaml
git add -p contracts/schemas/session-sse-event.schema.json
git add -p contracts/examples/sse/session-snapshot.json
git add -p contracts/examples/sse/execution-update.json
git add -p backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractExamplesTest.java
git add -p backend/agent-server/src/test/java/com/datagent/agentv2/conversation/stream/ConversationSseIntegrationTest.java
git diff --cached --check
git diff --cached
git commit -m "feat: stream conversation process message updates"
```

### Task 11: 在前端按 assistant 消息展示过程时间线

**Files:**
- Modify: `frontend/src/domain/agentPrototype.ts`
- Modify: `frontend/src/api/backendAgentClient.ts`
- Modify: `frontend/src/api/backendAgentClient.test.ts`
- Create: `frontend/src/components/MessageProcessTimeline.tsx`
- Create: `frontend/src/components/MessageProcessTimeline.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: discriminated TypeScript union `ThinkingProcessItem = ModelThinkingProcessItem | ToolThinkingProcessItem`; SSE reducer replaces messages by ID/version; `MessageProcessTimeline` uses `processItemId` as React key.
- Produces: `isProcessingStatus(AgentExecutionStatus): boolean`; 页面在没有处理中会话时保持零 SSE，最多同时持有一条 activity SSE 和一条当前会话 detail SSE。

- [ ] **Step 1: Write mapper, reducer, and UI tests**

```tsx
it('renders ordered process items under their assistant message', () => {
  render(<MessageProcessTimeline items={[thinkingItem, toolItem]} />);
  expect(screen.getAllByTestId('process-item').map(node => node.dataset.id))
    .toEqual([thinkingItem.processItemId, toolItem.processItemId]);
  expect(screen.getByText('12 ms')).toBeInTheDocument();
  expect(screen.getByText('输出已截断')).toBeInTheDocument();
});
```

Also test `thinkingProcess: []` renders nothing, failed tools display error code/message, `message_update` with higher version replaces one message, and duplicate/older versions are ignored.

Add connection-lifecycle tests with explicit close spies:

```tsx
it('opens streams only for processing sessions and closes them after terminal merge', async () => {
  const closeActivity = vi.fn();
  const closeDetail = vi.fn();
  let detailListener: Parameters<AgentBackendClient['subscribeSessionEvents']>[1] | undefined;
  const queuedSession: ConversationSession = {
    ...session,
    execution: {
      ...completedExecution('execution_1', ''),
      status: 'queued',
      progress: 0,
      streamCheckpoint: null,
    },
  };
  const backend = client({
    listSessions: vi.fn().mockResolvedValue([session]),
    submitMessage: vi.fn().mockResolvedValue(queuedSession),
    subscribeActivity: vi.fn(() => closeActivity),
    subscribeSessionEvents: vi.fn((_sessionId, listener) => {
      detailListener = listener;
      return closeDetail;
    }),
  });
  render(<App client={backend} />);

  expect(backend.subscribeActivity).not.toHaveBeenCalled();
  expect(backend.subscribeSessionEvents).not.toHaveBeenCalled();

  fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
  fireEvent.change(screen.getByLabelText('输入消息'), {
    target: { value: '分析订单延迟' },
  });
  fireEvent.click(screen.getByRole('button', { name: '发送消息' }));
  await waitFor(() => expect(backend.subscribeActivity).toHaveBeenCalledTimes(1));
  expect(backend.subscribeSessionEvents).toHaveBeenCalledWith(
    'session_1', expect.any(Function), expect.any(Function));

  act(() => detailListener?.({
    type: 'execution_update',
    conversationSessionId: 'session_1',
    execution: {
      executionId: 'execution_1', status: 'done', progress: 100,
      objective: '分析订单延迟', cancellationRequestedAt: null, streamCheckpoint: null,
    },
    event: {
      executionEventId: 'event_done', executionEventSequence: 2,
      type: 'execution_completed', occurredAt: '2026-08-04T02:00:00Z',
      title: '执行完成', summary: null,
    },
    messages: [{
      conversationMessageId: 'assistant_1', executionId: 'execution_1',
      role: 'assistant', author: 'assistant', content: '最终回答',
      timestamp: '2026-08-04T02:00:00Z', version: 2,
      thinkingProcess: [], execution: null,
    }],
  }));
  expect(closeDetail).toHaveBeenCalledTimes(1);
  expect(closeActivity).toHaveBeenCalledTimes(1);
  expect(screen.getByText('最终回答')).toBeInTheDocument();
});
```

Add a second test with two background processing sessions: switching to a terminal history page closes the old detail SSE but keeps exactly one activity SSE; opening another processing session replaces, rather than adds, the detail SSE. Assert `cancelExecution` is never called by connection cleanup.

- [ ] **Step 2: Run frontend tests and verify missing types/component**

```powershell
Set-Location .\frontend
npm.cmd test -- --run src/components/MessageProcessTimeline.test.tsx `
  src/api/backendAgentClient.test.ts src/App.test.tsx
Set-Location ..
```

Expected: FAIL because the process union, message-update reducer, and timeline component do not exist.

- [ ] **Step 3: Implement typed mapping and timeline rendering**

Use this discriminated base shape:

```ts
interface ProcessItemBase {
  processItemId: string;
  sequence: number;
  status: 'PREPARED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  durationMillis: number | null;
  schemaVersion: 1;
}

export type ThinkingProcessItem =
  | (ProcessItemBase & { type: 'THINKING'; content: string; contentTruncated: boolean;
      originalContentSizeBytes: number; contentSha256: string;
      modelInvocationSequence: number; modelProvider: string; modelName: string; })
  | (ProcessItemBase & { type: 'TOOL'; toolId: string; toolName: string;
      toolExecutionId: string; toolCallId: string; modelInvocationSequence: number;
      input: BoundedProcessPayload; output: BoundedProcessPayload | null;
      success: boolean | null; errorCode: string | null; errorMessage: string | null; });
```

Render only assistant items; default collapsed once terminal and open while RUNNING. Show status, timestamps, duration, model/tool identity, safe persisted content, and truncation metadata. Never attempt to fetch full output from logs. Keep the existing answer stream panel for in-flight answer text, but bind it to the same placeholder message ID and replace the message in place when final content arrives.

Centralize active-state recognition instead of scattering string comparisons:

```ts
const PROCESSING_STATUSES = new Set<AgentExecutionStatus>([
  'queued',
  'running',
  'waiting_for_user',
  'waiting_confirmation',
]);

export function isProcessingStatus(status: AgentExecutionStatus): boolean {
  return PROCESSING_STATUSES.has(status);
}
```

In `App.tsx`, derive `hasProcessingSession` from `session.execution?.status ?? session.activityStatus ?? 'idle'`. The activity effect returns without subscribing when false. The detail effect returns unless the routed `active` session is processing. Both effects return the unsubscribe callback, so status transition, route switch, backend replacement, and unmount close the old EventSource before another is opened. Apply a terminal `execution_update` (including its final message) to state before the derived status becomes terminal and triggers cleanup. On SSE error, perform one existing REST synchronization; do not create a retry loop when the refreshed state has no processing session.

- [ ] **Step 4: Run frontend tests and production build**

```powershell
Set-Location .\frontend
npm.cmd test -- --run
npm.cmd run build
Set-Location ..
```

Expected: all tests PASS and Vite production build exits 0.

- [ ] **Step 5: Commit only frontend task hunks**

Use `git add -p` for pre-existing dirty frontend files, stage the new component/test directly, inspect, then commit:

```powershell
git add frontend/src/components/MessageProcessTimeline.tsx `
  frontend/src/components/MessageProcessTimeline.test.tsx
git add -p frontend/src/domain/agentPrototype.ts
git add -p frontend/src/api/backendAgentClient.ts
git add -p frontend/src/api/backendAgentClient.test.ts
git add -p frontend/src/App.tsx
git add -p frontend/src/App.test.tsx
git add -p frontend/src/styles.css
git diff --cached --check
git diff --cached
git commit -m "feat: display assistant thinking process timeline"
```

### Task 12: 删除旧消息协议残留并执行全量验证

**Files:**
- Verify: all files changed by Task 1–11; this task is read-only unless a prior task's exact acceptance condition is not met, in which case return to that task before continuing.

**Interfaces:**
- Produces: no runtime or test reference to legacy internal conversation-message storage.
- Verifies: exactly one assistant center per execution, bounded nodes, complete safe audit, recovery, REST/SSE, and frontend.

- [ ] **Step 1: Run a legacy-reference scan**

```powershell
rg -n "toolCalls|tool_calls|visibility|MessageVisibility|observationConversationMessageId|observation_message_id|ConversationMessage\.observation|role.?=.?'tool'" `
  backend frontend contracts
```

Expected: only V1–V12 historical migrations and explicit migration-fixture assertions may match. Any production-code match is a defect to remove in this task.

- [ ] **Step 2: Run formatting/static diff checks before broad tests**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors. Confirm all unrelated pre-existing dirty files/hunks remain unstaged and unchanged by this implementation.

- [ ] **Step 3: Run the supported backend verification sequence**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web `
  -Dtest='com.datagent.agentv2.contracts.*Test' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

Expected: every command exits 0. If a Testcontainers test cannot start because Docker is unavailable, report the exact failing command and leave that verification explicitly unclaimed; do not weaken or skip the test in code.

- [ ] **Step 4: Run full frontend verification**

```powershell
Set-Location .\frontend
npm.cmd test -- --run
npm.cmd run build
Set-Location ..
```

Expected: tests and build exit 0.

- [ ] **Step 5: Verify root and subrepository boundaries**

```powershell
git status --short
git diff --cached --check
Set-Location ..
git status --ignore-submodules=none --short
git diff --ignore-submodules=none
Set-Location .\data-agent-v2
```

Expected: product commits exist only in `data-agent-v2`; root shows only the submodule pointer/worktree state and any pre-existing user changes. Do not commit the root submodule pointer unless the user separately requests it.

- [ ] **Step 6: Record verification evidence without creating an empty commit**

Copy the exit code and concise result of each Step 3–5 command into the execution checklist. If Step 1 found a production remnant, stop Task 12, return to the task that owns that exact file, add the missing failing assertion there, fix it, rerun that task, and amend only that task's unshared commit; do not create a catch-all cleanup commit.

## 完成验收清单

- [ ] 新提交或重试的 execution 有且只有一个 assistant 中心消息，真实 user 消息不重复。
- [ ] assistant 最终正文更新原占位行，工具调用和结果不产生额外 conversation_message 行。
- [ ] PostgreSQL 中 `thinking_process` 是有序 JSONB 数组，Java 读取结果是强类型 `List<ThinkingProcessItem>`。
- [ ] 每个节点 UUID、序号、状态、起止时间和耗时满足状态约束。
- [ ] tool 节点保存 toolId/name、toolExecutionId、toolCallId、完整安全入参/出参或有界预览、成功状态与错误。
- [ ] 三个单节点上限分别为 16 KiB、32 KiB、64 KiB，整条列表没有总大小拒绝逻辑。
- [ ] 所有终态节点无论是否截断均输出一次完整安全审计日志，并包含规定的主管审计注释。
- [ ] thinking 不进入模型上下文，工具上下文仅从数据库中的 Tool ProcessItem 重建，终态工具不重复执行。
- [ ] 完成、失败、取消、deadline 和 recovery exhaustion 后不存在未收敛节点。
- [ ] REST、SSE 和前端围绕同一 message ID/version 展示 `content + thinkingProcess`，断线后由 PostgreSQL 快照恢复。
- [ ] 没有处理中会话时页面占用零条 SSE；有后台执行时最多一条 activity SSE，且只有当前查看的处理中会话额外占用一条 detail SSE。
- [ ] 后端最小测试、全量 `verify`、前端测试和生产构建均通过，或明确记录不可用基础设施造成的唯一未验证项。
