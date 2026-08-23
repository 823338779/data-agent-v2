# 单实例本机执行简化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 删除工程中的执行调度与分布式运行设计，把后端收敛为单实例、事务提交后立即在本机虚拟线程执行，同时删除失去业务价值的表、列、状态和公开字段。

**架构：** PostgreSQL 只保存 execution、消息、过程节点、生命周期事件和回答检查点；本地 after-commit 事件启动 ReAct，本地 registry 管理 Future，本地应用事件驱动 SSE。LSM 与 hierarchical 保留算法和最终发布表，但压缩在当前 execution 内直接完成，不再持久化 job、owner、attempt 或恢复状态。

**技术栈：** Java 21、Spring Boot 4.1、Spring MVC/SSE、Spring 事务事件、虚拟线程、MyBatis-Plus、Flyway、PostgreSQL、JUnit 5、Testcontainers、React 19、TypeScript、Vitest。

**规格：** `docs/superpowers/specs/2026-08-24-single-instance-local-execution-simplification-design.md`

## 全局约束

- 只支持一个后端实例，不保留多实例兼容分支。
- 不兼容旧数据库；主迁移和插件迁移可重建为干净 V1 基线。
- 保留本机后台异步执行、取消、retry、ReAct、工具调用、SSE、LSM 和 hierarchical 压缩算法。
- 不引入 Redis 替代品、任务队列、通用调度框架、容量调度或恢复抽象。
- `agent-persistence` 继续统一承载 Entity、Mapper、ServiceImpl、迁移、ID、时钟和上下文基础设施。
- `agent-web` 继续承载 REST/SSE、应用编排和对话执行逻辑；`agent-server` 只负责启动与运行时装配。
- 人工审阅文档使用中文。
- 保留当前工作区未提交的 MessageRole 和 hierarchical migration 改动；遇到重叠文件时以最小补丁适配，不回退用户改动。
- 未经用户明确要求不创建 commit；每个任务以 diff 检查和测试结果作为检查点。

## 文件结构与职责

### 新增运行时文件

- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecution.java`：本机执行引用，只包含租户、会话、execution 和输入消息上界。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalExecutionStarted.java`：事务提交后启动 execution 的本地事件。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalExecutionCancelled.java`：事务提交后中断本机 Future 的本地事件。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionRegistry.java`：管理 `executionId -> FutureTask` 和虚拟线程执行器。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionLauncher.java`：消费启动/取消事件并调用 ReAct。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/StaleExecutionFinalizer.java`：应用启动时一次性关闭遗留 running execution。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/LocalExecutionProperties.java`：只保存 execution deadline。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/exception/ExecutionStoppedException.java`：表示 execution 已非 running 或已超过 deadline。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionDelta.java`：不带 cluster 语义的本地回答增量。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionDeltaMapper.java`：将 checkpoint 变化映射为本地 delta。

### 删除的运行时边界

- 删除 `conversation/runtime` 中 claim、queue scheduler、lease、heartbeat、recovery、worker identity、capacity、owner registry 和 fencing mapper。
- 删除 `conversation/cluster` 整包。
- 删除 `ReactRecoveryPlanner`、`ToolExecutionTransactionService`、Redis bridge、两个 reconciliation scheduler。

### 最终持久化边界

- 主库保留 `agent_execution`、`agent_execution_event`、`agent_execution_stream_checkpoint`、会话和消息等业务表。
- 删除 `agent_execution_attempt`、`notification_outbox_message`、`model_invocation`、`tool_execution`。
- LSM 保留 segment/manifest，删除 execution policy/job。
- Hierarchical 保留 cold/checkpoint，删除 execution policy/compaction job。

---

### 任务 1：重建执行领域模型和主数据库基线

**文件：**

- 创建：`backend/agent-persistence/src/main/resources/db/migration/V1__initial_schema.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V1__phase_1_streaming_chat.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V2__move_login_sessions_to_redis.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V3__multi_session_run_foundation.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V4__surrogate_identity_primary_keys.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V5__entity_business_identifier_columns.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V6__react_execution_recovery.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V7__use_local_audit_timestamps.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V8__cross_instance_streaming.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V9__clarify_conversation_execution_names.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V10__session_user_activity.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V11__use_beijing_database_timezone.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V12__remove_reasoning_storage.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V13__content_centered_conversation_messages.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V14__remove_internal_conversation_message_storage.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V15__move_identity_primary_keys_first.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V16__add_session_user_state_identity.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V17__scope_conversation_session_identity_to_tenant.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V18__remove_session_manual_ordering.sql`
- 删除：`backend/agent-persistence/src/main/resources/db/migration/V19__remove_session_user_state.sql`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/AgentExecution.java`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/AgentExecutionEvent.java`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/AgentExecutionStreamCheckpoint.java`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ToolThinkingProcessItem.java`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextExecutionRef.java`
- 修改：`backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/ContextExecutionControlPort.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/AgentExecutionEntity.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/AgentExecutionEventEntity.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/AgentExecutionStreamCheckpointEntity.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/AgentExecutionService.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/AgentExecutionStreamCheckpointService.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/json/ConversationProcessDocumentCodec.java`
- 修改：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapper.java`
- 删除：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/AgentExecutionAttemptEntity.java`
- 删除：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ModelInvocationEntity.java`
- 删除：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/NotificationOutboxMessageEntity.java`
- 删除：`backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ToolExecutionEntity.java`
- 删除：对应的 `AgentExecutionAttemptService`、`ModelInvocationService`、`NotificationOutboxMessageService`、`ToolExecutionService` 及四个 Mapper。
- 测试：`backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ConversationExecutionSchemaMigrationTest.java`
- 测试：`backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/ReactExecutionMigrationTest.java`
- 测试：`backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/json/ConversationProcessDocumentCodecTest.java`

**接口：**

- 产出：`AgentExecution.running(...)`、`AgentExecution.retry(...)`，只生成 running execution。
- 产出：`AgentExecutionEvent.occurred(eventId, sessionId, executionId, sequence, type, occurredAt)`，不接收 payload。
- 产出：`AgentExecutionStreamCheckpoint.initial(executionId, phase)`，不生成独立 checkpoint 业务 ID或 attempt。
- 产出：`ContextExecutionRef(tenantId, conversationSessionId, executionId, inputUpToMessageSequence)`。
- 产出：`ContextExecutionControlPort.assertRunningAndWithinDeadline(ContextExecutionRef)`。
- 后续依赖：任务 2 的本机 execution 生命周期和任务 4/5 的上下文插件。

- [ ] **步骤 1：先把 schema 测试改成目标表和目标列**

在 `ConversationExecutionSchemaMigrationTest` 增加明确断言：

```java
assertThat(tableNames())
        .doesNotContain(
                "agent_execution_attempt",
                "notification_outbox_message",
                "model_invocation",
                "tool_execution");

assertThat(columnNames("agent_execution"))
        .contains("execution_id", "conversation_session_id", "status", "hard_deadline_at")
        .doesNotContain(
                "execution_attempt_number",
                "owner_user_id",
                "owner_instance_id",
                "request_id",
                "queued_at",
                "lease_expires_at",
                "heartbeat_at",
                "cancellation_requested_at",
                "retry_of_execution_id",
                "recovery_attempt_count",
                "execution_budget_ms",
                "active_execution_ms",
                "active_segment_started_at",
                "phase",
                "started_at",
                "completed_at",
                "failed_at");

assertThat(columnNames("agent_execution_event")).doesNotContain("payload");
assertThat(columnNames("agent_execution_stream_checkpoint"))
        .doesNotContain(
                "execution_stream_checkpoint_id",
                "execution_attempt_number",
                "checkpointed_at");
```

- [ ] **步骤 2：运行迁移测试，确认旧基线不满足目标**

运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dtest=ConversationExecutionSchemaMigrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：FAIL，报告旧表或旧列仍存在。

- [ ] **步骤 3：为精简后的 ProcessItem JSON 写失败测试**

在 `ConversationProcessDocumentCodecTest` 构造工具节点并断言：

```java
String document = codec.write(List.of(toolItem));
assertThat(document)
        .contains("\"toolName\"")
        .contains("\"toolCallId\"")
        .doesNotContain("\"toolId\"")
        .doesNotContain("\"toolExecutionId\"")
        .doesNotContain("\"success\"");
```

运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am `
  '-Dtest=ConversationProcessDocumentCodecTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：FAIL，旧 JSON 仍包含三个冗余字段。

- [ ] **步骤 4：重建主 V1 基线**

删除 V1–V19，创建 `V1__initial_schema.sql`。保留当前最终版本中与本次无关的 tenant、user、membership、conversation session/message 等表；执行核心表采用以下目标形状：

```sql
create table agent_execution (
    id bigint generated always as identity primary key,
    execution_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    triggering_conversation_message_id varchar(64) not null,
    status varchar(32) not null,
    request_type varchar(32) not null,
    request_idempotency_key varchar(256) not null,
    request_payload_hash char(64) not null,
    failure_code varchar(64),
    failure_detail text,
    hard_deadline_at timestamptz not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint ck_agent_execution_status
        check (status in ('running', 'done', 'cancelled', 'failed')),
    constraint uq_agent_execution_tenant_id unique (tenant_id, execution_id),
    constraint uq_agent_execution_request
        unique (tenant_id, conversation_session_id, request_type, request_idempotency_key)
);

create unique index uq_agent_execution_one_active
    on agent_execution (tenant_id, conversation_session_id)
    where is_deleted = false and status = 'running';

create table agent_execution_stream_checkpoint (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    execution_id varchar(64) not null,
    model_invocation_sequence bigint not null default 0,
    phase varchar(32) not null,
    output_delta_sequence bigint not null default 0,
    answer text not null default '',
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_execution_stream_execution
        unique (tenant_id, execution_id)
);
```

为 execution event 建立 append-only 表，不创建 payload 列；外键、租户复合唯一键、会话事件序号索引和软删除约束沿用仓库现有命名规范。

- [ ] **步骤 5：精简 domain/entity 和映射**

将 `AgentExecution` 的工厂收敛为：

```java
public static AgentExecution running(
        String executionId,
        String conversationSessionId,
        String triggeringMessageId,
        String requestType,
        String idempotencyKey,
        String payloadHash,
        Instant hardDeadlineAt) {
    AgentExecution execution = new AgentExecution();
    execution.executionId = executionId;
    execution.conversationSessionId = conversationSessionId;
    execution.triggeringConversationMessageId = triggeringMessageId;
    execution.status = "running";
    execution.requestType = requestType;
    execution.requestIdempotencyKey = idempotencyKey;
    execution.requestPayloadHash = payloadHash;
    execution.hardDeadlineAt = hardDeadlineAt;
    execution.version = 0L;
    return execution;
}
```

`retry(...)` 只复用会话、触发消息和幂等输入，不保存 retry 链。删除 domain/entity 中第 6.2 节列出的字段。

- [ ] **步骤 6：精简持久化服务接口**

`AgentExecutionService` 最终只保留执行主链路需要的方法：

```java
AgentExecution lockById(String executionId);
AgentExecution lockActiveByConversationSessionId(String conversationSessionId);
AgentExecution findByExecutionId(String executionId);
AgentExecution findRequest(String sessionId, String requestType, String idempotencyKey);
List<AgentExecution> findRunning(int limit);
boolean completeRunning(String executionId, long expectedVersion);
boolean failRunning(
        String executionId, long expectedVersion, String failureCode, String failureDetail);
boolean cancelRunning(String executionId, long expectedVersion);
```

checkpoint service 的条件更新只匹配 `executionId + version + modelInvocationSequence`，不匹配 owner 或 attempt。

- [ ] **步骤 7：删除四组恢复/通知持久化类型并修正 Mapper 扫描测试**

删除 attempt、model invocation、notification outbox、tool execution 的 Entity、Service 和 Mapper。调整 `AgentPersistenceTestApplication` 或 mapper 配置，使启动时不再扫描已删除接口。

- [ ] **步骤 8：运行 domain/persistence 验证**

运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am test
```

预期：BUILD SUCCESS；迁移只执行新的 V1，schema 与 codec 测试通过。

- [ ] **步骤 9：记录检查点**

运行 `git diff --stat` 和 `git status --short`，确认只包含任务 1 范围和已有用户改动，不提交。

---

### 任务 2：切换本机 execution 生命周期、ReAct 和工具执行

**文件：**

- 创建：本计划“新增运行时文件”中的 `LocalAgentExecution`、两个本地事件、registry、launcher、finalizer、properties 和 `ExecutionStoppedException`。
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionService.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionTransactionService.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationService.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationProcessCoordinator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactExecutionEngine.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ConversationContextLoader.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ConversationContextRequestFactory.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/WebContextExecutionControlAdapter.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolExecutionCoordinator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionStreamCheckpointCoordinator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionStreamAccumulator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionStreamKey.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ReactRecoveryPlanner.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/tool/ToolExecutionTransactionService.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionClaimService.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionClaimTransactionService.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionLeaseService.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionPort.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionQueueScheduler.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionRecoveryScheduler.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionRuntimeProperties.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/AgentExecutionRuntimeStructMapper.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ExecutionOwnerRegistry.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ExecutionWorkerCapacity.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ExecutionWorkerIdentity.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/runtime/ReactExecutionAdapter.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/exception/ExecutionOwnershipLostException.java`
- 测试：新增 `backend/agent-web/src/test/java/com/datagent/agentv2/conversation/runtime/LocalAgentExecutionRegistryTest.java`
- 测试：新增 `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/LocalAgentExecutionLifecycleIntegrationTest.java`
- 测试：修改 submission、control、process、ReAct、tool 和 context loader 现有测试。
- 测试：删除 lease recovery、worker capacity、React crash recovery 测试。

**接口：**

- 消费：任务 1 的精简 execution/checkpoint/context port。
- 产出：`LocalAgentExecution` 和 `LocalAgentExecutionRegistry.start/cancel`。
- 产出：提交和 retry 事务发布 `LocalExecutionStarted`；取消事务发布 `LocalExecutionCancelled`。
- 产出：ReAct 与工具层只依赖 `LocalAgentExecution` 和 assistant `thinking_process`。
- 后续依赖：任务 3 的本地 SSE，任务 4/5 的插件运行边界。

- [ ] **步骤 1：为 registry 写并发和生命周期失败测试**

```java
@Test
void startsEachExecutionOnceAndCancelsItsFuture() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    assertThat(registry.start("execution_1", () -> awaitInterrupt(started, interrupted))).isTrue();
    assertThat(registry.start("execution_1", () -> {})).isFalse();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.cancel("execution_1")).isTrue();
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
}
```

运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=LocalAgentExecutionRegistryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：FAIL，因为本机 registry 尚不存在。

- [ ] **步骤 2：实现本机 execution 引用和 registry**

`LocalAgentExecution`：

```java
public record LocalAgentExecution(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long inputUpToMessageSequence) {
    public LocalAgentExecution {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        if (inputUpToMessageSequence < 0L) {
            throw new IllegalArgumentException("inputUpToMessageSequence must not be negative");
        }
    }
}
```

registry 使用 `Executors.newVirtualThreadPerTaskExecutor()`，先把 `FutureTask` 放入 map 再执行，任务 `finally` 中使用 `remove(executionId, futureTask)`，避免极短任务先结束后注册的竞态。

- [ ] **步骤 3：为 after-commit 启动和直接取消写集成失败测试**

`LocalAgentExecutionLifecycleIntegrationTest` 覆盖：

```java
assertThat(submitResponse.status()).isEqualTo("running");
verify(modelGateway, timeout(5_000).times(1)).invoke(any());

mockMvc.perform(cancelRequest).andExpect(status().isOk());
await().atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(registry.contains(executionId)).isFalse());
```

另写回滚用例：在事务内抛出异常后 `verifyNoInteractions(modelGateway)`。

运行目标测试，预期 FAIL：旧实现仍创建 queued 并依赖 scheduler。

- [ ] **步骤 4：把提交和 retry 改为直接创建 running**

`AgentExecutionSubmissionTransactionService` 使用 `LocalExecutionProperties.executionDeadline()` 计算 `hardDeadlineAt`，创建 started event 和初始 `RECEIVED` checkpoint，并在事务内发布：

```java
applicationEvents.publishEvent(
        new LocalExecutionStarted(
                new LocalAgentExecution(
                        TenantContext.requiredTenantId(),
                        conversationSessionId,
                        execution.getExecutionId(),
                        sequences.lastConversationMessage())));
```

幂等 replay 不发布第二个启动事件。`AgentExecutionControlService.retry(...)` 使用同一工厂和事件。

- [ ] **步骤 5：实现 launcher、直接取消与启动清理**

launcher 的启动入口：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void start(LocalExecutionStarted event) {
    LocalAgentExecution execution = event.execution();
    boolean accepted = registry.start(
            execution.executionId(),
            () -> TenantContext.run(
                    execution.tenantId(), () -> runAndFinalize(execution)));
    if (!accepted) {
        termination.failRunning(
                execution.executionId(),
                "LOCAL_EXECUTION_DUPLICATE",
                "Execution was already started");
    }
}
```

取消事务直接关闭过程节点并更新 execution；after-commit listener 只负责 `registry.cancel(executionId)`。`StaleExecutionFinalizer` 实现 `ApplicationRunner`，应用启动时只运行一次：分批查找 running 并以 `PROCESS_RESTARTED` 标记失败，不发布启动事件。

- [ ] **步骤 6：移除 owner/attempt/lease 检查**

所有原 `ClaimedAgentExecution` 参数改为 `LocalAgentExecution`。共享校验统一为：

```java
private AgentExecution requireRunning(LocalAgentExecution execution, Instant now) {
    AgentExecution current = executions.lockById(execution.executionId());
    if (current == null
            || !ExecutionStatus.RUNNING.value().equals(current.getStatus())
            || !current.getHardDeadlineAt().isAfter(now)) {
        throw new ExecutionStoppedException(execution.executionId());
    }
    return current;
}
```

deadline 超时由 termination 原子关闭 execution，再抛出 `ExecutionDeadlineExceededException`。

- [ ] **步骤 7：删除 model invocation 和 tool execution 双重事实源**

ReAct 循环本地维护：

```java
long modelInvocationSequence = 0L;
while (true) {
    modelInvocationSequence++;
    ModelInvocationBoundary boundary =
            progress.beginModelInvocation(
                    execution, modelInvocationSequence, inputUpToMessageSequence);
    ModelInvocationResult result = model.invoke(messages, tools);
    // final answer 或按返回顺序执行 tools
}
```

`ModelInvocationCoordinator` 不再读写 `ModelInvocationService` 或 `ToolExecutionService`。`ToolExecutionCoordinator` 接收 `List<ModelToolCall>`，逐项执行：

```java
public List<ToolExecutionResult> executeModelInvocationTools(
        LocalAgentExecution execution,
        long modelInvocationSequence,
        List<ModelToolCall> calls);
```

每个 call 在 I/O 前持久化 PREPARED/RUNNING ProcessItem，I/O 后持久化 SUCCEEDED/FAILED；不创建工具行，不做 crash replay。

- [ ] **步骤 8：让上下文重建只读取 thinking_process**

删除 `ConversationContextLoader`、LSM/hierarchical history loader 中对 `ToolExecutionService` 行的交叉验证。按 `modelInvocationSequence` 聚合 ProcessItem：先生成 assistant tool calls，再为 terminal 工具节点生成 observation。provider `toolCallId` 作为协议配对键。

- [ ] **步骤 9：更新过程、流和终态事务**

`ConversationProcessCoordinator`、`ExecutionStreamCheckpointCoordinator` 和 `AgentExecutionProcessTerminationService` 只锁 execution 状态和 checkpoint version。完成回答时按顺序：flush checkpoint、更新 assistant 正文、把 execution 改为 done、写 `execution_done` 事件。

- [ ] **步骤 10：运行 agent-web 非契约测试**

运行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=LocalAgentExecutionRegistryTest,ToolExecutionCoordinatorTest,ModelThinkingProcessTest,ConversationApplicationServiceTest,WebContextExecutionControlAdapterTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：BUILD SUCCESS。

- [ ] **步骤 11：运行本机生命周期集成测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dtest=LocalAgentExecutionLifecycleIntegrationTest,AgentExecutionSubmissionIntegrationTest,AgentExecutionControlIntegrationTest,ReactExecutionEngineIntegrationTest,ConversationProcessCoordinatorIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：BUILD SUCCESS，测试不设置 `scheduling-enabled=false`，也不需要 Redis。

- [ ] **步骤 12：记录检查点**

检查删除文件和 `git diff --stat`；确认 `AgentExecutionLeaseRecoveryIntegrationTest`、`ExecutionWorkerCapacityIntegrationTest`、`ReactCrashRecoveryIntegrationTest` 已删除且无替代恢复逻辑。

---

### 任务 3：移除 Redis、定时收敛并建立本地 SSE 屏障

**文件：**

- 创建：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionDelta.java`
- 创建：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionDeltaMapper.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/LocalSseRegistry.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionStreamCheckpointCoordinator.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/CompositeSseCursor.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/summary/ConversationSummaryStreamFacade.java`
- 修改：`backend/agent-web/pom.xml`
- 修改：`backend/agent-server/src/main/java/com/datagent/agentv2/AgentV2Application.java`
- 修改：`backend/agent-server/src/main/resources/application.yml`
- 修改：`backend/agent-server/src/main/resources/application-business.yml`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/cluster/` 下全部文件。
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/SessionEventReconciler.java`
- 删除：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/summary/ConversationSummaryReconciler.java`
- 删除测试：`ExecutionCancellationOutboxIntegrationTest.java`、`RedisStreamDeltaIntegrationTest.java` 和 cluster mapper 测试。
- 修改测试：`ConversationSseIntegrationTest.java`、`ConversationSummarySseIntegrationTest.java`、`AgentExecutionStreamCheckpointIntegrationTest.java`、`AgentV2ApplicationTest.java`。

**接口：**

- 消费：任务 2 的本机 execution 与精简 checkpoint。
- 产出：`ExecutionDelta(executionId, modelInvocationSequence, outputDeltaSequence, delta, occurredAt)`。
- 产出：SSE open 流程为“注册本地连接 -> 读快照 -> resume 游标 -> 一次补查 -> 实时事件”。
- 后续依赖：任务 6 的前端归约逻辑。

- [ ] **步骤 1：写无 Redis 应用启动失败测试**

从 `AgentV2ApplicationTest` 删除 Redis 动态属性，只提供 PostgreSQL；断言 Spring context 能启动。运行测试，预期旧 `StringRedisTemplate`/listener bean 导致 FAIL。

- [ ] **步骤 2：写连接屏障和即时 gap 重同步测试**

在 `ConversationSseIntegrationTest` 控制以下时序：注册连接后、首次 snapshot 查询返回前写入一个新 checkpoint；断言客户端最终只看到连续坐标且不依赖 sleep 到下一次 scheduler。

```java
assertThat(receivedDeltaSequences()).containsExactly(1L, 2L);
verify(snapshotLoader, atLeastOnce()).load(conversationSessionId);
```

运行目标测试，预期 FAIL 或超时。

- [ ] **步骤 3：把 cluster notification 改为本地 ExecutionDelta**

移动并精简 record：

```java
public record ExecutionDelta(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long modelInvocationSequence,
        long outputDeltaSequence,
        String delta,
        Instant occurredAt) {}
```

删除 attempt 坐标。`ExecutionStreamKey` 只使用 executionId/modelInvocationSequence；新模型调用从 delta 1 建立新投影。

- [ ] **步骤 4：精简 LocalSseRegistry**

删除 `ChannelLifecycle`、`attachLifecycle`、subscribe/unsubscribe。保留 register、publish、resume 和连接级 gap 检测。注册不得产生任何外部 I/O。

- [ ] **步骤 5：实现一次性连接屏障**

`ConversationEventStreamFacade.open(...)` 按以下顺序：

```java
LocalSseRegistry.Registration registration = registry.register(...);
Snapshot initial = loadSnapshot(conversationSessionId);
sendSnapshot(emitter, initial);
registration.resume(initial.executionId(), initial.modelInvocationSequence(), initial.deltaSequence());
reconcileOnce(conversationSessionId, registration, emitter, initial.cursor());
```

实时 onGap 立即调用同一个 `reconcileOnce`。删除 public `reconcileActiveSessions()`。

- [ ] **步骤 6：让会话摘要由本地持久化事件驱动**

`ConversationSummaryStreamFacade` 监听 `AgentExecutionEventPersisted` 和 `ConversationMessageChanged` 的 after-commit 回调，只为当前本机订阅者重新查询并发送相应会话摘要。删除 `reconcileSubscribers()`。

- [ ] **步骤 7：删除 Redis、scheduler 和配置**

- 从 `agent-web/pom.xml` 删除 `spring-boot-starter-data-redis`。
- 从 `application.yml` 删除 `spring.data.redis`。
- 从 `application-business.yml` 删除 queue/control/reconcile/lease/heartbeat/scheduling 配置，只保留 `data-agent.runtime.execution-deadline`。
- 从 `AgentV2Application` 删除 `@EnableScheduling`。
- 删除 cluster 包、reconciler 和 Redis 测试。

- [ ] **步骤 8：运行 SSE 与启动验证**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dtest=AgentV2ApplicationTest,ConversationSseIntegrationTest,ConversationSummarySseIntegrationTest,AgentExecutionStreamCheckpointIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：BUILD SUCCESS；日志不尝试连接 Redis，不启动 scheduled task。

- [ ] **步骤 9：运行 Redis 残留扫描**

```powershell
rg -n -i 'Redis|StringRedisTemplate|RedisConnectionFactory|notification_outbox|@Scheduled|EnableScheduling' `
  backend/agent-web/src backend/agent-server/src backend/agent-web/pom.xml
```

预期：无运行时代码匹配；如果测试说明文字保留历史名，应删除或改为本地语义。

---

### 任务 4：把 LSM 压缩改为内联计划、生成和发布

**文件：**

- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmContextAutoConfiguration.java`
- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/configuration/LsmMapperConfiguration.java`
- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmCompactionCoordinator.java`
- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmManifestPublicationTransaction.java`
- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/LsmConversationContextAssembler.java`
- 修改：`backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/repository/LsmContextManifestRepository.java`
- 修改：`backend/agent-context-lsm/src/main/resources/db/agent-context-lsm/migration/V1__create_lsm_context_store.sql`
- 删除：`backend/agent-context-lsm/src/main/resources/db/agent-context-lsm/migration/V2__remove_lsm_warm_window.sql`
- 删除：`LsmExecutionPolicyEntity/Mapper/Repository`
- 删除：`LsmCompactionJobEntity/Mapper/Repository`
- 删除：`LsmCompactionPlanningTransaction.java`
- 删除：`LsmCompactionRecoveryService.java`
- 删除：只描述持久 job 状态的 `LsmCompactionException` 和 coordinator 内 `JobStatus`。
- 修改测试：`LsmCompactionCoordinatorTest.java`、`LsmRepositoryIntegrationTest.java`、`LsmManifestPublicationIntegrationTest.java`、`LsmConversationContextStrategyTest.java`。
- 删除测试：`LsmCompactionRecoveryTest.java`、`LsmContextCompactionRecoveryIntegrationTest.java`。

**接口：**

- 消费：任务 1 的精简 `ContextExecutionRef`/control port。
- 产出：`compactIfRequired(CompactionRequest)` 在一次调用中完成 plan -> generate -> CAS publish。
- 产出：只持久化 summary segment 和 manifest。
- 后续依赖：任务 8 的全仓验证。

- [ ] **步骤 1：写“没有 job/policy 表仍能压缩”失败测试**

在 `LsmRepositoryIntegrationTest` 断言：

```java
assertThat(tableNames())
        .contains("conversation_context_lsm_segment", "conversation_context_lsm_manifest")
        .doesNotContain(
                "conversation_context_lsm_job",
                "conversation_context_lsm_execution_policy");
```

在 coordinator 测试中 mock planner/generator/publication，断言一次 `compactIfRequired` 顺序调用三者且没有 job repository。

运行 LSM 测试，预期 FAIL。

- [ ] **步骤 2：重写 LSM 插件 V1**

V1 只创建 segment 与 manifest、插件 Flyway history 所需对象和索引。manifest 保留 generation/version、active segment IDs、覆盖范围和有效 fanout/maxLevel；删除 execution policy/job 外键和索引。

- [ ] **步骤 3：删除 job/policy 类型和自动配置**

从 mapper configuration、auto configuration 和构造器删除对应 bean。当前运行配置通过 `LsmContextProperties` 直接生成 `LsmContextPolicySnapshot`，仅在本次内存计划中使用。

- [ ] **步骤 4：把 coordinator 收敛为单调用流程**

目标骨架：

```java
public CompactionOutcome compactIfRequired(CompactionRequest request) {
    controls.assertRunningAndWithinDeadline(request.execution());
    LsmCompactionPlan plan = planner.plan(request.planningInput());
    if (plan.operations().isEmpty()) {
        return CompactionOutcome.unchanged(request.currentManifest());
    }
    List<LsmSummarySegment> generated = generator.generate(plan, request.sources());
    controls.assertRunningAndWithinDeadline(request.execution());
    return publication.publish(request.currentManifest(), plan, generated);
}
```

保留 publication 的 manifest version/generation CAS；CAS 冲突最多在当前调用内重新加载并重新规划一次，不创建 durable job。

- [ ] **步骤 5：删除 tool execution 行依赖**

在 `LsmConversationContextAssembler` 中仅从消息 `thinkingProcess` 生成 assistant tool calls 和 observations。保留当前工作区对 MessageRole 和事件摘要的未提交改动。

- [ ] **步骤 6：运行 LSM 模块测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-lsm -am test
```

预期：BUILD SUCCESS；测试名称和断言不包含 recovery、owner、attempt 或 job lifecycle。

- [ ] **步骤 7：运行 LSM 服务集成测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dtest=LsmContextCompactionIntegrationTest,ContextCompactionSecurityIntegrationTest,ContextPluginActivationIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：BUILD SUCCESS。

---

### 任务 5：把 hierarchical 压缩改为内联滚动 Cold 发布

**文件：**

- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalContextAutoConfiguration.java`
- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/configuration/HierarchicalMapperConfiguration.java`
- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalCompactionCoordinator.java`
- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java`
- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/repository/HierarchicalCheckpointRepository.java`
- 修改：`backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/planning/HierarchicalConversationHistoryLoader.java`
- 修改：当前未提交的 hierarchical 插件 migration，最终只保留一个 `V1__create_hierarchical_context_store.sql`。
- 删除：`HierarchicalExecutionPolicyEntity/Mapper/Repository`
- 删除：`HierarchicalCompactionJob.java`
- 删除：`HierarchicalCompactionJobEntity/Mapper/Repository`
- 修改测试：`HierarchicalFlywayIntegrationTest.java`、`HierarchicalCompactionCoordinatorTest.java`、`HierarchicalConversationHistoryLoaderTest.java`、`HierarchicalContextCompactionIntegrationTest.java`。

**接口：**

- 消费：任务 1 的精简 context execution ref。
- 产出：`compactIfRequired(execution, state, input, targetTokens)` 直接生成 cold 并 CAS 更新 checkpoint。
- 产出：只持久化 hierarchical cold 与 checkpoint。

- [ ] **步骤 1：写目标 schema 和直接发布失败测试**

`HierarchicalFlywayIntegrationTest` 断言：

```java
assertThat(tableNames())
        .contains(
                "conversation_context_hierarchical_cold",
                "conversation_context_hierarchical_checkpoint")
        .doesNotContain(
                "conversation_context_hierarchical_compaction_job",
                "conversation_context_hierarchical_execution_policy");
```

coordinator 测试断言 generator 成功后直接 `coldRepository.insert(...)` 和 `checkpointRepository.publish(...)`，失败时 checkpoint 指针不变。

运行 hierarchical 模块目标测试，预期 FAIL。

- [ ] **步骤 2：收敛插件 V1 migration**

基于当前工作区正在调整的 rolling cold migration，最终创建：

- `conversation_context_hierarchical_cold`
- `conversation_context_hierarchical_checkpoint`
- 覆盖会话、消息范围和 active cold 的必要唯一键/外键/索引

不创建 execution policy、compaction job、owner 或 attempt 列。删除多步兼容迁移。

- [ ] **步骤 3：删除 policy/job 类型和 bean**

从 auto configuration、mapper configuration 和 coordinator 构造器移除 policy/job repository。`HierarchicalContextProperties` 在当前调用中构造策略值，不写 execution policy 表。

- [ ] **步骤 4：实现内联 rolling cold 流程**

目标骨架：

```java
public CompactionOutcome compactIfRequired(
        ContextExecutionRef execution,
        HierarchicalContextState state,
        CompactionInput input) {
    controls.assertRunningAndWithinDeadline(execution);
    RollingColdCompactionPlan plan = planner.plan(input);
    if (!plan.compactionRequired()) {
        return new CompactionOutcome(state.checkpoint(), false);
    }
    HierarchicalCold candidate = generator.generate(plan);
    controls.assertRunningAndWithinDeadline(execution);
    coldRepository.insert(candidate);
    HierarchicalCheckpoint published =
            checkpointRepository.publish(state.checkpoint(), candidate);
    return new CompactionOutcome(published, true);
}
```

checkpoint 更新只使用会话、base version 和 strategy generation CAS。

- [ ] **步骤 5：让历史加载只读 thinking_process**

删除 `HierarchicalConversationHistoryLoader` 对 `ToolExecutionEntity` 的读取和三字段交叉校验，直接从 `ToolThinkingProcessItem.toolCallId/toolName/input/output` 重建模型消息。

- [ ] **步骤 6：运行 hierarchical 模块测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
```

预期：BUILD SUCCESS，并保留当前用户的 rolling cold 与 MessageRole 变更。

- [ ] **步骤 7：运行 hierarchical 服务集成测试**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dtest=HierarchicalContextCompactionIntegrationTest,ContextCompactionSecurityIntegrationTest,ContextPluginActivationIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：BUILD SUCCESS。

---

### 任务 6：收敛 REST/SSE 契约、View 和前端状态

**文件：**

- 修改：`contracts/openapi.yaml`
- 修改：`contracts/schemas/session-sse-event.schema.json`
- 修改：`contracts/schemas/task-execution-sse-event.schema.json`
- 修改：`contracts/inventories/features.yaml`
- 删除：`contracts/examples/rest/session-queued.yaml`
- 修改：`contracts/examples/rest/session-running.yaml`
- 修改：`contracts/examples/sse/assistant-delta.json`
- 修改：`contracts/examples/sse/stream-snapshot.json`
- 修改：其他引用 attempt/queued/confirmation 的 contract examples。
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ExecutionStatus.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/SessionExecutionStatus.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/enums/ExecutionEventType.java`
- 删除：`ModelInvocationStatus.java`、`ToolExecutionStatus.java`。
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationView.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/conversation/session/ConversationApplicationService.java`
- 修改：`backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationController.java`
- 修改：backend contract tests 和 view composition tests。
- 修改：`frontend/src/domain/agentPrototype.ts`
- 修改：`frontend/src/api/backendAgentClient.ts`
- 修改：`frontend/src/api/backendAgentClient.test.ts`
- 修改：`frontend/src/components/ConversationStatus.tsx`
- 修改：`frontend/src/components/MessageProcessTimeline.tsx`
- 修改：`frontend/src/components/RunStreamPanel.tsx`
- 修改：`frontend/src/App.tsx` 与 `frontend/src/App.test.tsx`。

**接口：**

- 消费：任务 3 的 `ExecutionDelta` 坐标。
- 产出：execution status 仅 `running/done/cancelled/failed`；会话摘要可用 `idle`。
- 产出：stream checkpoint 仅 `modelInvocationSequence/phase/outputDeltaSequence/answer`。
- 产出：工具过程不含 `toolId/toolExecutionId/success`。

- [ ] **步骤 1：先修改契约测试表达目标字段**

在 `BusinessEnumSerializationTest`：

```java
assertThat(ExecutionStatus.values())
        .containsExactly(
                ExecutionStatus.RUNNING,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED,
                ExecutionStatus.DONE);
```

在 contract path/schema 测试断言 confirmation/interaction 路径不存在，Schema 中不存在：

```text
queued
lost
waiting_for_user
waiting_confirmation
executionAttemptNumber
cancellationRequestedAt
pendingConfirmation
pendingInteraction
toolExecutionId
toolId
success
```

运行 agent-web contract tests，预期 FAIL。

- [ ] **步骤 2：精简 OpenAPI、JSON Schema 和 examples**

提交与 retry 保持 `202`，返回 execution status `running`。删除 confirmation/interaction 路径、Schema 和 event 占位字段。stream snapshot/delta 使用：

```yaml
required:
  - executionId
  - modelInvocationSequence
  - outputDeltaSequence
  - phase
  - occurredAt
```

snapshot 额外包含 `answer`，delta 额外包含 `delta`。

- [ ] **步骤 3：精简后端枚举和 View 工厂**

`ConversationView.AgentExecutionView`：

```java
public record AgentExecutionView(
        String executionId,
        ExecutionStatus status,
        int progress,
        String objective,
        List<AgentExecutionEventView> events,
        AgentExecutionStreamCheckpointView streamCheckpoint) {}
```

progress 读取 checkpoint phase；checkpoint 为空时 running=5、done=100、failed/cancelled=0。event view 只保留 event ID、sequence、type、occurredAt、executionId 和 status。

- [ ] **步骤 4：更新前端类型和 delta 归约**

前端 checkpoint：

```ts
export interface AgentExecutionStreamCheckpoint {
  modelInvocationSequence: number;
  phase: string;
  outputDeltaSequence: number;
  answer: string;
}
```

`applyStreamEvent` 按 `(executionId, modelInvocationSequence, outputDeltaSequence)` 去重；不比较 attempt。ConversationStatus 删除排队、等待和 lost 文案。MessageProcessTimeline 只展示 toolName、toolCallId、输入、输出和错误。

- [ ] **步骤 5：运行 backend contract tests**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  '-Dtest=com.datagent.agentv2.contracts.*Test,ConversationViewCompositionTest' test
```

预期：BUILD SUCCESS。

- [ ] **步骤 6：运行前端单元测试**

```powershell
Set-Location .\frontend
npm.cmd test
```

预期：所有 Vitest 测试通过，无 queued/attempt fixture。

- [ ] **步骤 7：运行前端构建**

```powershell
Set-Location .\frontend
npm.cmd run build
```

预期：Prettier、TypeScript 和 Vite 构建全部成功。

---

### 任务 7：清理配置、README、历史设计和验证说明

**文件：**

- 修改：`README.md`
- 修改：`contracts/inventories/features.yaml`
- 删除：`docs/superpowers/specs/2026-08-01-multi-session-agent-execution-status-design.md`
- 修改：`docs/superpowers/specs/2026-08-01-backend-module-boundaries-design.md`
- 修改：`docs/superpowers/plans/2026-08-01-backend-module-boundaries.md`
- 修改：`docs/superpowers/specs/2026-08-23-running-conversation-steering-design.md`
- 修改：`docs/superpowers/plans/2026-08-23-running-conversation-steering.md`
- 修改：`docs/superpowers/specs/2026-08-23-event-oriented-compact-context-design.md`
- 修改：`docs/superpowers/plans/2026-08-23-event-oriented-context-compaction.md`
- 修改：`docs/tool-skill-capability-design.md` 中受本次设计影响的 lease、接管、outbox 段落；保留该未跟踪文档的其他用户内容。
- 修改：`docs/contracts.md` 中执行/SSE 部署说明。

**接口：**

- 消费：任务 1–6 的最终代码和契约事实。
- 产出：所有长期阅读文档只描述单实例本机执行；历史文档不再被当作当前设计依据。

- [ ] **步骤 1：先建立文档残留扫描清单**

运行：

```powershell
rg -n -i '双实例|多实例|跨实例|Redis|lease|heartbeat|ownerInstance|executionAttempt|queued|Outbox|接管|领取|reconciliation' `
  README.md docs contracts/inventories
```

保存需要删除或改写的命中位置；设计文档中用于说明“已删除内容”的文字允许保留，但必须明确是历史/非目标。

- [ ] **步骤 2：重写 README 运行说明**

README 只要求 PostgreSQL 与模型 provider。说明：

```text
消息提交后由接收请求的唯一后端实例在本机虚拟线程执行。
服务重启不会恢复未完成 execution；遗留 running 会在启动时标记失败。
```

删除 Redis 凭据、双实例启动命令、调度/租约/收敛配置和跨实例承诺。

- [ ] **步骤 3：删除或改写历史分布式设计**

删除以数据库排队、lease takeover 和 Redis 多实例为核心的多会话 execution 设计。其余文档按当前代码改为本地 Future 取消、内联压缩和本地 SSE。不得把被删除机制写成“未来默认恢复”的隐藏承诺。

- [ ] **步骤 4：更新 feature inventory 和 contracts 说明**

删除 `multi-instance-redis-notification`、队列领取和接管特性。新增或保留：

```yaml
- id: single-instance-local-execution
  entry: "单实例本机后台执行"
  success: "提交事务后在本机虚拟线程启动并通过 PostgreSQL 保存状态"
  failure: "进程重启后遗留执行进入失败，不自动重放"
  security: "过程正文仅进入既有数据库与本机 SSE"
  phase: 1
```

- [ ] **步骤 5：复跑文档扫描**

对 README、contracts inventory 和当前设计文档逐项阅读命中。预期：没有把 Redis、queue、lease、attempt、owner 或多实例描述为受支持运行能力。

- [ ] **步骤 6：记录检查点**

用 `git status --short` 确认没有删除用户无关文档；尤其检查 `docs/tool-skill-capability-design.md` 仍保留未受影响章节。

---

### 任务 8：全仓残留扫描与最终验证

**文件：**

- 修改：只修复本任务验证暴露出的、与本规格直接相关的问题。
- 不新增功能或兼容层。

**接口：**

- 消费：任务 1–7 的全部产出。
- 产出：完整构建和残留扫描证据。

- [ ] **步骤 1：扫描删除的 Java 类型和包**

```powershell
rg -n 'AgentExecutionClaim|AgentExecutionLease|ExecutionWorkerIdentity|ExecutionWorkerCapacity|ExecutionOwnerRegistry|ReactRecoveryPlanner|ToolExecutionService|ModelInvocationService|NotificationOutbox|ExecutionOwnershipLostException' backend
```

预期：无生产代码或测试匹配；设计文档中的历史说明不在本扫描范围。

- [ ] **步骤 2：扫描删除的数据库对象和字段**

```powershell
rg -n -i 'agent_execution_attempt|notification_outbox_message|model_invocation|tool_execution|owner_instance_id|lease_expires_at|heartbeat_at|execution_attempt_number|queued_at|recovery_attempt_count' `
  backend/*/src/main contracts frontend/src README.md
```

预期：无匹配。PowerShell 不展开 `backend/*/src/main` 时，改为分别传入各模块 `src/main` 目录，不能跳过扫描。

- [ ] **步骤 3：扫描运行配置和契约状态**

```powershell
rg -n -i 'Redis|scheduling-enabled|queue-scan|control-poll|reconcile-interval|lease-duration|heartbeat-interval|queued|waiting_for_user|waiting_confirmation|lost' `
  backend/agent-server/src/main backend/agent-web/src/main contracts frontend/src README.md
```

预期：无已删除运行能力匹配；`SessionExecutionStatus.IDLE` 可以保留。

- [ ] **步骤 4：运行完整后端验证**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

预期：BUILD SUCCESS，所有 Maven 模块成功，无测试失败。

- [ ] **步骤 5：运行完整前端验证**

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
```

预期：Vitest、Prettier、TypeScript 和 Vite 全部成功。

- [ ] **步骤 6：检查最终 diff 和用户改动保护**

```powershell
git status --short
git diff --stat
git diff --check
```

预期：`git diff --check` 无空白错误；现有 MessageRole、hierarchical rolling cold 和其他用户改动没有被回退。

- [ ] **步骤 7：逐条核对规格完成标准**

对照规格第 11 节七项完成标准，记录每项对应的测试或扫描命令。如果任何命令失败，报告实际失败和剩余风险，不声明完成。

- [ ] **步骤 8：交付但不提交**

向用户汇报修改范围、删除的表/列/能力、验证命令与结果。除非用户另行授权，不执行 `git add`、`git commit`、创建分支或 push。
