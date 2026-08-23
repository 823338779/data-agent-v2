# 单实例本机执行与分布式设计删减方案

## 1. 状态

- 设计日期：2026-08-24。
- 设计状态：已完成分段确认，等待书面规格复核。
- 部署边界：只支持单个后端实例。
- 数据兼容：不兼容现有开发数据库，允许重建 Flyway 基线和数据库。
- 保留能力：本机后台异步执行、ReAct、工具调用、取消、重试、REST/SSE、LSM 与 hierarchical 上下文压缩算法。
- 删除目标：执行排队与领取、分布式所有权、租约与接管、崩溃续跑、Redis/Outbox、定时收敛，以及只为这些能力存在的业务字段、数据库列和中间表。

## 2. 背景与问题

当前执行主链路由以下机制共同组成：

```text
queued -> 数据库扫描 -> 用户容量锁 -> worker claim -> lease/heartbeat
       -> attempt fencing -> 执行 -> Redis/Outbox 通知 -> PostgreSQL 定时收敛
       -> lease 过期后由其他实例接管或恢复
```

这套设计把多实例调度和故障恢复扩散到了执行域、持久化、ReAct、工具调用、上下文压缩、SSE、前端契约和测试。当前阶段明确只运行单实例，继续保留这些机制会让正常本机执行承担大量没有产品收益的状态和条件分支。

本次改造以“只有一条本机执行路径”为约束，直接删除分布式设计，不保留兼容层、废弃字段或空实现。

## 3. 已确认的设计决策

### 3.1 本机后台异步执行

消息提交不绑定 HTTP 请求生命周期。数据库事务提交后，当前进程立即使用虚拟线程启动 ReAct。系统不使用数据库队列，也不把 execution 交给其他实例。

### 3.2 单实例部署

多个后端实例连接同一数据库不属于受支持部署方式。删除跨实例取消、跨实例 SSE、worker identity、lease owner 和多实例接管相关代码与文档。

### 3.3 不兼容旧数据库

主 Flyway 与两个上下文插件的迁移整理为干净基线。开发环境需要删除并重建数据库；不增加用于兼容旧表和旧字段的过渡迁移。

### 3.4 保留上下文压缩算法

LSM 和 hierarchical 继续负责长会话摘要选择、生成和最终发布，但压缩成为当前 ReAct execution 内的普通同步步骤。压缩不再拥有独立 job、claim、owner、attempt、恢复或接管生命周期。

## 4. 目标架构

### 4.1 唯一执行路径

```text
提交事务
  -> 创建 running execution、用户消息、assistant 占位消息、started 事件、空流检查点
  -> 事务提交后发布 LocalExecutionStarted
  -> LocalAgentExecutionLauncher 提交虚拟线程
  -> ReAct / 模型 / 工具 / 上下文压缩
  -> PostgreSQL execution、assistant thinking_process、事件和流检查点
  -> 事务提交后发布本地 SSE 事件
```

### 4.2 本机运行组件

- `LocalAgentExecutionLauncher`：监听事务提交后的启动事件，提交虚拟线程；提交失败时把 execution 收敛为 `failed`。
- `LocalAgentExecutionRegistry`：只保存 `executionId -> Future`，支持本机取消和应用关闭；不提供队列、容量、owner 或恢复语义。
- `LocalAgentExecution`：只携带 `tenantId`、`conversationSessionId`、`executionId` 和输入消息上界，不携带实例 ID、attempt 或 lease。
- `LocalExecutionDeltaPublisher`：监听事务提交后的回答增量事件，直接投递本机 SSE registry。
- `StaleExecutionFinalizer`：应用启动时执行一次，把遗留的 `running` execution 标记为 `failed`；它不是定时任务，也不重放任何工作。

### 4.3 事务边界

1. 提交事务直接创建 `running` execution，不出现 `queued` 中间态。
2. 启动事件只在提交成功后发布，回滚事务不会启动模型。
3. ReAct 的模型、工具、过程节点、流检查点和终态写入仍使用短事务。
4. 每次业务写入先锁定 execution 并确认状态仍为 `running`。
5. 状态校验只防止本机取消或终态之后的迟到写入，不表达分布式所有权。

## 5. 状态、并发与故障语义

### 5.1 状态机

删除 `queued`、`lost`、`waiting_for_user`、`waiting_confirmation` 状态，删除 `QUEUED` phase 和 `execution_queued` 事件。后面三种状态当前都没有生产写入方，只形成了状态机和公开契约占位。目标状态转换为：

```text
running -> done
running -> failed
running -> cancelled
```

retry 不复活原 execution，而是创建新的 `running` execution。

### 5.2 并发

- 删除每用户最多三个 running execution 的规则。
- 删除每实例最多 256 个 execution 的容量组件。
- 删除本机和数据库排队；不同会话提交后立即创建虚拟线程。
- 保留数据库部分唯一索引，保证同一会话最多一个 `running` execution。
- `STEER_ACTIVE` 在一个事务内终止旧 execution、写入新用户消息并创建新 execution。

### 5.3 取消

1. 取消事务锁定 execution，关闭开放的过程节点，并直接把状态改为 `cancelled`。
2. 事务提交后发布本地取消事件。
3. registry 对对应 Future 调用 `cancel(true)`。
4. 迟到的模型、工具、摘要和 checkpoint 写入因 execution 已不是 `running` 而失败。
5. 保留最后一次已经持久化的部分回答；不保证保留仍在本机缓冲区中的少量增量。

### 5.4 超时与异常

- execution 创建时根据当前配置计算一次 `hard_deadline_at`。
- 模型调用、工具调用和上下文压缩边界检查 execution 仍为 `running` 且未超过截止时间。
- after-commit 任务提交失败、ReAct 未处理异常、模型异常、工具边界异常或压缩异常统一收敛为 `failed`。
- 删除 heartbeat、lease 过期和 recovery exhausted 等错误分类。

### 5.5 关闭与重启

- 优雅关闭时 registry 取消全部 Future，并尽力把仍在运行的 execution 标记为 `failed`。
- 进程崩溃时无法执行关闭逻辑；下一次启动由 `StaleExecutionFinalizer` 一次性关闭遗留 `running` execution。
- 不恢复模型流，不恢复工具调用，不重新生成摘要，不创建新 attempt。

## 6. 数据库与业务字段瘦身

字段删除遵循三个判断条件：没有独立业务语义、只服务于已删除的分布式/恢复能力、或者已经有明确的唯一事实源。满足条件的字段直接从 domain、entity、mapper、service、migration、contract 和测试中一并删除。

### 6.1 整表删除

#### 主持久化

- `agent_execution_attempt`：只记录 worker 领取、接管和 attempt 终态。
- `notification_outbox_message`：只负责跨实例取消通知。
- `model_invocation`：其 sequence、模型信息和过程状态已存在于 assistant `thinking_process`；其余字段只服务 crash recovery。
- `tool_execution`：工具输入、输出、状态、错误和模型调用序号已存在于 assistant `thinking_process`；独立表主要服务恢复、fencing 和重复执行判断。

删除 `model_invocation` 和 `tool_execution` 后，assistant 消息的 `thinking_process` 成为模型/工具过程的唯一持久化事实源。工具仍按以下短边界执行：先持久化 PREPARED/RUNNING 过程节点，再执行外部工具，最后持久化 SUCCEEDED/FAILED 结果。

#### LSM 插件

- `conversation_context_lsm_execution_policy`：execution 级策略冻结只用于重试和接管。
- `conversation_context_lsm_job`：删除完整 compaction job 生命周期。

保留 LSM segment 与 manifest 表。运行时配置直接参与当前压缩；已发布 manifest/segment 自身包含算法继续工作所需的覆盖范围、层级和生成信息。

#### Hierarchical 插件

- `conversation_context_hierarchical_execution_policy`：execution 级策略冻结只用于重试和接管。
- `conversation_context_hierarchical_compaction_job`：删除完整 compaction job 生命周期。

保留 hierarchical cold 与 checkpoint 表，分别作为不可变摘要和当前会话发布指针。

### 6.2 `agent_execution` 字段

删除以下字段：

| 字段 | 删除原因 |
| --- | --- |
| `execution_attempt_number` | 不再领取、接管或恢复 |
| `owner_user_id` | 只用于用户级并发调度 |
| `owner_instance_id` | 不再存在分布式 owner |
| `request_id` | 当前没有读取方，不参与业务幂等 |
| `queued_at` | 不再排队 |
| `lease_expires_at` | 不再租约续期或接管 |
| `heartbeat_at` | 不再发送心跳 |
| `cancellation_requested_at` | 取消直接终态化，不再异步轮询请求 |
| `retry_of_execution_id` | 原执行和 retry 均可由消息/事件时间线独立观察，不保留恢复链 |
| `recovery_attempt_count` | 不再恢复 |
| `execution_budget_ms` | 无累计 attempt 预算；创建时直接计算 deadline |
| `active_execution_ms` | 不再跨等待和 attempt 累计预算 |
| `active_segment_started_at` | 不再结算活跃片段 |
| `phase` | 与 stream checkpoint 的 phase 重复 |
| `started_at` | execution 创建即运行，可使用审计 `created_at` |
| `completed_at` | 终态时间由持久 execution event 表达 |
| `failed_at` | 终态时间由持久 execution event 表达 |

保留以下业务字段：

- `execution_id`
- `conversation_session_id`
- `triggering_conversation_message_id`
- `status`
- `request_type`
- `request_idempotency_key`
- `request_payload_hash`
- `failure_code`
- `failure_detail`
- `hard_deadline_at`
- 通用租户、审计、软删除和乐观锁字段

其中 `request_type + request_idempotency_key + request_payload_hash` 继续承担提交与 retry 的幂等语义；`hard_deadline_at` 直接承担本次本机 execution 的截止时间，不再由 lease 或累计预算派生。

### 6.3 `agent_execution_stream_checkpoint` 字段

删除：

- `execution_stream_checkpoint_id`：没有业务消费者，一条 execution 只对应一个 checkpoint。
- `execution_attempt_number`：不再存在 attempt。
- `checkpointed_at`：与通用 `updated_at` 重复。

保留：

- `execution_id`，作为唯一业务键。
- `model_invocation_sequence`，区分 ReAct 中的不同模型调用。
- `phase`，作为唯一执行进度事实源。
- `output_delta_sequence`，用于同一模型调用内的 SSE 去重与缺口判断。
- `answer`，用于断线重连恢复部分回答。
- 通用审计与乐观锁字段。

### 6.4 `thinking_process` 字段

删除工具过程节点中的冗余字段：

- `toolId`：当前与 `toolName` 写入相同值，只保留 `toolName`。
- `toolExecutionId`：删除独立 `tool_execution` 表后不再有被引用对象；provider 的 `toolCallId` 足以维持模型协议配对。
- `success`：与节点 `status=SUCCEEDED/FAILED` 重复。

保留 `processItemId`、顺序、状态、时间、模型调用序号、工具名称、provider tool call ID、安全处理后的输入/输出以及失败码/失败信息。模型与工具节点继续使用统一 schema version。

### 6.5 execution event 与占位业务字段

`agent_execution_event.payload` 当前所有生产写入都是 `null` 或空对象，读取和公开投影也不消费该值。删除 domain/entity 的 `payload` 字段和数据库列；事件只保存身份、会话、execution、序号、类型和发生时间。

删除未实现业务留下的占位字段和枚举：

- `ExecutionStatus.LOST`、`WAITING_FOR_USER`、`WAITING_CONFIRMATION`。
- `ExecutionEventType.COMPLETED`、`WAITING`、`PHASE_CHANGED` 等没有生产写入方的枚举值。
- execution view 的 `pendingConfirmation`、`pendingInteraction`。
- event view 中始终为空或由固定占位文案组成的 `conversationMessageId`、`title`、`summary`、`toolName`、`input`、`output`、`confirmationId`、`approved`。
- 没有实现服务端行为的 confirmation/interaction OpenAPI 路径与 Schema。

工具输入、输出、错误和时间线展示统一读取 assistant `thinking_process`，不再在 execution event 中保留第二套占位结构。

### 6.6 上下文执行引用与控制端口

`ContextExecutionRef` 删除：

- `ownerInstanceId`
- `executionAttempt`

保留：

- `tenantId`
- `conversationSessionId`
- `executionId`
- `inputUpToMessageSequence`

`ContextExecutionControlPort.assertOwnedAndWithinBudget(...)` 改为表达本地业务状态的 `assertRunningAndWithinDeadline(...)`；取消检查改为直接判断 execution 是否仍为 `running`。插件不再理解 owner、lease 或接管。

### 6.7 明确保留的字段

以下字段虽然可以进一步压缩，但仍有独立业务价值，本次保留：

- `request_type`、幂等键和负载 hash：防止重复提交产生第二次执行。
- `failure_code`、`failure_detail`：支持稳定失败分类和本机诊断。
- `model_invocation_sequence`：重建工具调用/observation 顺序，并隔离 SSE 模型轮次。
- `output_delta_sequence`：支持客户端去重和即时缺口检测。
- `hard_deadline_at`：本机异步任务仍需要确定的执行截止时间。
- manifest/checkpoint 的 generation 与 version：防止同一进程内并发更新丢失，不表达分布式所有权。

本次不顺带删除租户、用户、消息作者或通用审计字段；这些属于认证/租户模型的独立重设计范围。

## 7. ReAct、工具与上下文压缩改造

### 7.1 ReAct

- 删除 `ReactRecoveryPlanner`。
- ReAct 循环本地维护从 1 开始的 `modelInvocationSequence`。
- 模型调用开始、thinking、工具调用和结果直接更新 assistant `thinking_process`。
- `LocalAgentExecution` 替代带 fencing 信息的 `ClaimedAgentExecution`。

### 7.2 工具执行

- 删除 `ToolExecutionService`、Entity、Mapper 以及基于行状态的恢复事务。
- `ToolExecutionCoordinator` 接收本次模型直接返回的 `ModelToolCall` 列表，按顺序执行。
- 工具外部 I/O 仍不包在数据库事务内。
- 调用前后分别使用短事务更新对应 ProcessItem。
- 超时、异常脱敏和结果边界继续保留。
- 不再自动重放有副作用的工具；进程中断后整个 execution 在下次启动时失败。

### 7.3 LSM 与 hierarchical

- 删除 compaction planning job 的持久化、claim、recovery 和 publish fencing 服务。
- planner 仍生成内存计划。
- generator 仍生成事件化摘要文档。
- publication 只对 manifest/checkpoint 使用会话级乐观锁或 CAS。
- 生成失败时保留上一份已发布摘要，并使当前 execution 失败或按现有上下文失败规则处理。

## 8. SSE 与公开契约

### 8.1 本地实时流

- 删除 Redis Pub/Sub 和动态 channel 订阅。
- `LocalSseRegistry` 不再拥有外部 channel lifecycle。
- execution、消息或 checkpoint 事务提交后，通过 Spring 本地事件直接投递 registry。
- 删除 `SessionEventReconciler` 和 `ConversationSummaryReconciler` 定时任务。

### 8.2 重连

- PostgreSQL 事件、消息和 stream checkpoint 继续作为重连快照来源。
- 首次连接和重连先注册本地连接，再读取数据库快照并恢复游标，最后按序号执行一次补查；该连接屏障覆盖“注册与快照”之间的并发事件，但不启动周期任务。
- 客户端发现 delta 序号缺口时立即读取一次快照，不等待周期收敛。

### 8.3 REST/OpenAPI

- 会话创建、消息提交、取消、retry、详情和 SSE 路径保持不变。
- 消息提交与 retry 仍返回 `202 Accepted`，初始 execution 状态改为 `running`。
- execution status 只保留 `running`、`done`、`failed`、`cancelled`；会话没有 execution 时仍可在会话摘要投影中使用 `idle`。
- 删除公开契约中的 `queued`、`lost`、两个 waiting 状态、`executionAttemptNumber` 和 `cancellationRequestedAt`。
- stream snapshot/delta 保留 `modelInvocationSequence` 和 `outputDeltaSequence`。
- execution `progress` 从 stream checkpoint phase 计算；没有 checkpoint 时按 execution status 给出默认值。
- 删除 `session-queued` 示例和前端“排队中”状态。
- 工具过程契约删除 `toolId`、`toolExecutionId` 和 `success`。
- 删除未实现的 confirmation/interaction 路径、Schema 和始终为空的 execution/event view 字段。

## 9. 配置、依赖与文档

删除配置：

- `max-concurrent-executions-per-instance`
- `queue-scan-interval`
- `control-poll-interval`
- `session-event-reconcile-interval`
- `conversation-summary-reconcile-interval`
- `lease-duration`
- `heartbeat-interval`
- `scheduling-enabled`
- `control-outbox-poll-interval`
- 全部 Redis 连接配置

只保留本机 execution deadline/模型 timeout/工具 timeout 等实际执行边界。

从 `agent-web` 删除 `spring-boot-starter-data-redis`，从测试中删除 Redis Testcontainer。README 改为单实例启动说明，删除双实例、Redis、租约、领取、接管和收敛参数章节。

以执行调度和分布式为主题、已不再代表目标系统的历史设计与计划文档直接删除。仍有价值的上下文压缩设计改写为单实例、内联压缩和最终发布语义。

## 10. 测试策略

### 10.1 契约测试

- OpenAPI、JSON Schema、示例和前端类型中不存在 `queued`、`lost`、waiting、attempt、owner、lease、cancellation request 和跨实例通知字段。
- 工具过程契约中不存在 `toolId`、`toolExecutionId` 和 `success`。
- 不存在没有实现方的 confirmation/interaction 路径和占位 view 字段。
- 提交与 retry 的初始状态为 `running`。

### 10.2 持久化测试

- 新基线不创建 attempt、outbox、model invocation、tool execution、context job 和 execution policy 表。
- `agent_execution` 与 stream checkpoint 只包含第 6 节保留字段。
- execution event 不包含 payload 列，状态与事件约束不包含未使用枚举值。
- 同一会话活动 execution 唯一约束仍有效。
- 提交与 retry 幂等唯一约束仍有效。
- assistant `thinking_process` 能完整保存并读取模型和工具过程。

### 10.3 本机运行测试

- 提交事务回滚时不启动任务，提交成功后只启动一次。
- 同会话活动冲突仍返回稳定 409。
- `STEER_ACTIVE` 取消旧 Future 并启动新 execution。
- 取消后迟到 checkpoint、工具结果和摘要发布被拒绝。
- after-commit 提交任务失败时 execution 进入 `failed`。
- 应用启动时遗留 `running` execution 被关闭，不产生新模型或工具调用。
- retry 创建新 execution 并保持幂等。

### 10.4 SSE 测试

- 本地 delta 在事务提交后实时投递。
- 重连从 PostgreSQL 快照恢复部分回答。
- 重复 delta 被忽略，跳号触发即时快照重同步。
- 不启动 Redis 或 reconciliation scheduler 也能通过全部 SSE 用例。

### 10.5 上下文测试

- LSM/hierarchical planner、摘要生成、覆盖范围和最终发布测试继续保留。
- 删除 job claim、owner fencing、lease takeover 和 crash recovery 测试。
- 验证压缩失败不覆盖上一份已发布摘要。

### 10.6 验证顺序

1. 运行 agent-domain 与 agent-persistence 测试。
2. 运行 agent-context-lsm 与 agent-context-hierarchical 测试。
3. 运行 agent-web 契约与单元测试。
4. 运行 agent-server 集成测试。
5. 运行前端测试与生产构建。
6. 运行 `backend` 完整 Maven `verify`。
7. 使用 `rg` 扫描 Redis、lease、heartbeat、owner instance、execution attempt、queued、outbox、recovery scheduler 和已删除表名，确认运行时代码、配置、契约及目标文档中没有残留。

## 11. 完成标准

1. 运行时只有“事务后本机立即执行”一条路径。
2. 工程不依赖 Redis，不包含数据库任务队列、领取调度器、lease、heartbeat、attempt 或跨实例接管。
3. execution、stream、工具过程和上下文插件的数据模型完成第 6 节字段瘦身。
4. ReAct、工具调用、取消、retry、SSE 和两种上下文压缩算法保持可用。
5. 应用重启只关闭遗留 execution，不恢复或重放工作。
6. README、契约、前端和测试只描述单实例语义。
7. 全部范围匹配测试和完整 Maven 验证通过。

## 12. 取舍与非目标

- 不提供多实例部署、高可用接管或跨节点 SSE。
- 不提供持久执行队列、全局容量控制或每用户并发额度。
- 不保证进程崩溃时保留最后一次内存 checkpoint 之后的增量。
- 不自动重放模型调用、工具调用或上下文压缩。
- 不在本次重设计认证、租户隔离、消息作者或通用审计模型。
- 不新增替代 Redis 的消息总线、任务框架或通用调度抽象。

这些取舍是有意的：当前阶段优先保持单实例主链路清晰，让未来确有多实例需求时基于实际约束重新设计，而不是继续维护尚未需要的分布式机制。
