# 多会话 Agent 执行 执行、恢复与状态展示设计

> 历史说明：本文记录最初方案。用户级会话状态、手工排序和已读/未读能力已分别由 V18、V19 迁移移除，不再代表当前产品契约。

## 1. 背景与目标

Data Agent V2 当前在提交消息时同步创建 `running` Agent 执行，但没有真正的后台模型执行器、持续事件发布、租约恢复或终态收敛。服务重启后，数据库中的 Agent 执行 可能永久停留在 `running`，后续消息因此触发 `uq_agent_execution_one_active` 唯一约束。

本设计实现以下目标：

- 不同会话可以同时执行，页面切换、SSE 断开或浏览器关闭不影响后台 Agent 执行。
- 同一用户最多同时执行 3 个 `running` Agent 执行，超出的 Agent 执行 持久化为 `queued` 并按提交时间排队。
- 同一会话最多存在一个非终态 Agent 执行，避免上下文并发修改。
- 使用 Ollama 执行真实流式模型调用，分别处理模型明确返回的 reasoning tokens 和最终回答 tokens。
- reasoning、回答、阶段和 checkpoint 持久化；切换会话、断线重连和服务器重启后可以恢复显示。
- 服务器重启后通过租约自动接管孤儿 Agent 执行；无法安全恢复时进入明确的失败状态并允许重试。
- 前端会话列表显示排队中、思考中、待回复、待确认、失败、取消和未读圆点。
- 未读和用户拖拽顺序跨设备持久化。
- reasoning 内容只保留 30 天，最终回答、Agent 执行 元数据和普通事件继续保留。

## 2. 已确认的产品规则

### 2.1 并发与排队

- 每个用户最多同时拥有 3 个 `running` Agent 执行。
- `waiting_for_user` 和 `waiting_confirmation` 不占用模型执行槽。
- 第 4 个及后续 Agent 执行 创建为 `queued`，有空闲执行槽后按 `queued_at` 升序自动领取。
- `queued` 是非终态，纳入“同一会话最多一个非终态 Agent 执行”的数据库约束。
- 新会话默认排在用户会话列表顶部。

### 2.2 未读

- 未读状态按租户、用户和会话持久化，跨浏览器和设备同步。
- 前端只显示一个未读圆点，不显示未读数量。
- `done`、`failed`、`waiting_for_user`、`waiting_confirmation` 在用户未停留于该会话时产生未读。
- 普通 reasoning、answer 流式增量和阶段进度不产生未读。
- 用户进入会话时立即标记当前已知序号为已读；停留期间的新可通知事件自动推进已读游标。
- 标记已读使用显式的 `upToSequence`，不得用无条件布尔清除，以免并发到达的新事件被误清除。

### 2.3 排序

- 会话顺序由用户拖拽决定，状态和活动变化不自动重排。
- 顺序由服务端按用户持久化并跨设备同步。
- 拖拽提交使用排序版本进行乐观并发控制；冲突返回 409，前端刷新后提示重新操作。

### 2.4 reasoning 与取消

- 只有 Ollama 接口明确返回的 reasoning tokens 才原样展示；系统不生成或推断未暴露的内部推理。
- reasoning tokens 实时展示并持久化，支持切换会话和恢复。
- reasoning 保存 30 天。到期后只清空 reasoning；最终回答、Agent 执行 状态、耗时和不含 reasoning 正文的普通事件继续保留。
- `queued`、`running` 和 waiting 状态都允许取消。
- 取消后保留已经生成的 reasoning 和部分回答，并标记“已取消，回答可能不完整”。

## 3. 总体架构

PostgreSQL 是 Agent 执行、租约、事件、流投影、未读和排序的唯一事实来源。Redis 只承担跨节点唤醒和实时失效通知；Redis 消息丢失不得导致任务、进度或未读状态丢失。

### 3.1 组件边界

- `agent-persistence`
  - 提供 Flyway 增量迁移、Entity、Mapper 和通用持久化 Service。
  - 不包含 Agent 执行 领取、状态转换或业务重试决策。
- `agent-web`
  - 承载会话与 Agent 执行 应用逻辑、队列调度、Ollama 适配、checkpoint、REST/SSE、未读和排序。
  - 其他模块不得直接使用 Mapper 或 MyBatis API。
- `agent-server`
  - 只负责 Spring Boot 启动、运行时装配、定时任务启用和全局 Problem Detail。
  - 不放置产品业务状态机。
- `agent-evals`
  - 通过公开 HTTP/SSE 契约评估真实运行行为。
  - 不成为任何运行时模块的依赖。

### 3.2 运行数据流

1. 用户提交消息。
2. 一个数据库事务写入用户消息、`queued` Agent 执行、`execution_queued` 事件、空 stream 投影和通知 Outbox。
3. HTTP 返回 202；模型执行不依赖原始请求继续存活。
4. 调度器发现 queued 通知或周期扫描，尝试为该用户领取执行槽。
5. 领取事务写入 `execution_started`；执行器随后调用 Ollama，实时发布 reasoning/answer，并按 checkpoint 阈值持久化。
6. Redis 将轻量通知广播到持有 SSE 的节点。
7. 执行成功时，同一事务把最终 answer 固化为 assistant message，并更新 Agent 执行、`execution_completed` 事件、会话可通知序号和 Outbox；失败、等待或取消使用对应状态事件完成同类收敛。
8. 前端的用户级 SSE 更新所有会话的列表状态；当前会话详细 SSE 更新 reasoning、answer 和时间线。

## 4. 数据模型

所有变更通过新的 Flyway 迁移完成，不修改已经发布的 `V1__phase_1_streaming_chat.sql`。

### 4.1 `agent_execution`

新增或调整字段：

- `status`：增加 `queued`。
- `owner_user_id varchar(64) not null`：用于用户级并发控制和队列选择。
- `queued_at timestamptz`：排队时间。
- `lease_expires_at timestamptz`：当前 owner 租约截止时间。
- `heartbeat_at timestamptz`：最近成功续租时间。
- `cancellation_requested_at timestamptz`：取消请求时间。
- `retry_of_execution_id varchar(64)`：手动重试创建的新 Agent 执行 指向原失败 Agent 执行。

`hard_deadline_at` 在 Agent 执行 被领取时计算，排队时间不消耗模型执行时限。`execution_attempt_number` 在每次实际领取或租约接管时递增。

重建部分唯一索引 `uq_agent_execution_one_active`，条件覆盖：

```sql
status in ('queued', 'running', 'waiting_for_user', 'waiting_confirmation')
```

### 4.2 `agent_execution_stream_checkpoint`

继续作为当前 attempt 的可读取投影，保留：

- `execution_attempt_number`
- `phase`
- `output_delta_sequence`
- `thinking`
- `answer`
- `checkpointed_at`

新增 `reasoning_expires_at`。每次 checkpoint 原子更新完整投影和递增的 `output_delta_sequence`。接管新 attempt 时，新投影替换旧 attempt 的实时内容，前端根据 `executionAttemptNumber` 重置本地增量拼接状态。

### 4.3 `agent_execution_attempt`

新增 attempt 审计表，主键为 `(tenant_id, execution_id, execution_attempt_number)`，保存：

- attempt 状态、owner、开始/结束时间和失败码。
- 被接管或结束时的 partial reasoning、partial answer 和 phase。
- `reasoning_expires_at`。

旧 attempt 用于审计和恢复诊断，不作为当前会话的实时投影。30 天清理同时清空该表中过期的 reasoning。

### 4.4 `conversation_session`

新增：

- `last_activity_at timestamptz`：用于时间展示，不参与用户自定义排序。
- `last_notifiable_execution_event_sequence bigint not null default 0`：最近一次应产生未读的会话事件序号。

### 4.5 用户会话状态

新增 `conversation_session_user_state`：

- 主键：`(tenant_id, user_id, conversation_session_id)`。
- `sort_order bigint not null`。
- `last_read_execution_event_sequence bigint not null default 0`。
- 审计时间和乐观锁 `version`。

新增 `agent_conversation_preference`：

- 主键：`(tenant_id, user_id)`。
- `ordering_version bigint not null default 0`。

未读判断为：

```text
conversation_session.last_notifiable_execution_event_sequence
    > conversation_session_user_state.last_read_execution_event_sequence
```

完整重排在一个事务内锁定 `agent_conversation_preference`，验证 `ordering_version`，将提交的可见会话 ID 依次写入连续的 `sort_order`，最后递增版本。新会话通过当前最小 `sort_order - 1` 置顶；完整重排时重新压缩为连续整数，避免长期溢出。

### 4.6 reasoning 清理

每日维护任务只处理终态 Agent 执行，并清空以下位置中 `reasoning_expires_at <= now()` 的 reasoning：

- `agent_execution_stream_checkpoint.thinking`
- `agent_execution_attempt` 的 partial reasoning

事件表不得持久化原始 reasoning 正文；reasoning/answer 增量通过 stream checkpoint 和实时 SSE 表达，避免清理时遗漏副本。

## 5. Agent 执行 状态机与并发控制

### 5.1 状态转换

允许的主要转换：

```text
queued -> running
queued -> cancelled
running -> done
running -> failed
running -> cancelled
running -> waiting_for_user
running -> waiting_confirmation
waiting_for_user -> queued
waiting_confirmation -> queued
waiting_for_user -> cancelled
waiting_confirmation -> cancelled
```

自动租约接管不改变 `running` 状态，但递增 `execution_attempt_number` 并更换 owner。非法转换返回稳定的 409 Problem Detail。

手动重试不复活终态 Agent 执行，而是创建一个新的 `queued` Agent 执行，并通过 `retry_of_execution_id` 保留审计关系。原 Agent 执行 的 partial 内容和终态保持不变。

### 5.2 每用户三个执行槽

领取事务按以下顺序执行：

1. 锁定该用户的 `user_account` 行，序列化同一用户在不同节点上的领取判断。
2. 统计该用户租户内状态为 `running` 且未逻辑删除的 Agent 执行。
3. 数量达到 3 时结束，不领取任务。
4. 使用 `FOR UPDATE SKIP LOCKED` 选择该用户最早的 queued Agent 执行。
5. 更新为 `running`，设置 owner、attempt、started/heartbeat/lease/deadline，并创建 attempt 记录。
6. 提交后开始 Ollama 调用。

数据库锁只覆盖短事务，不覆盖模型执行过程。

### 5.3 租约与重启恢复

默认配置：

- 租约：60 秒。
- 心跳：每 15 秒。
- 单次 Agent 执行 硬截止时间：10 分钟。
- 最大自动 attempt：3。

维护扫描器查找 `running` 且 `lease_expires_at < now()` 的 Agent 执行。接管仍需使用行锁和条件更新，确保只有一个实例成为新 owner。

恢复规则：

- 在持久化阶段边界中断时，从最近 checkpoint 继续。
- Ollama 不支持从指定 Token 精确续传。流式调用中断时归档旧 attempt，以新 attempt 从最近稳定消息边界重新生成，并重置当前 stream 投影。
- 已完成且具有幂等键的外部工具调用可以从完成 checkpoint 之后继续。
- 无幂等保证且结果不确定的副作用不得自动重放；Agent 执行 进入 `failed` 并允许用户显式重试。
- 连续达到 3 个 attempt 后进入 `failed`。

## 6. Ollama 流与 checkpoint

Ollama 适配器通过 Spring AI 的 Ollama 流式模型接口输出两类增量：

- provider 明确返回的 reasoning tokens。
- 面向用户的最终 answer tokens。

适配器不得把普通回答、日志或自行生成的摘要伪装为 reasoning。

Token 先通过当前节点实时发布，再由 checkpoint 累加器按以下任一阈值批量持久化：

- 距离上次 checkpoint 达到 250 毫秒。
- 新增内容达到 512 个字符。
- phase 变化、等待、完成、失败、取消或租约即将释放。

checkpoint 写入完整 `thinking`/`answer` 投影并递增 `output_delta_sequence`。节点崩溃时最多丢失尚未达到 checkpoint 阈值的实时 Token；接管时从最后持久化投影恢复。Agent 执行 进入任一非 running 状态前必须执行最终同步 checkpoint。

## 7. Redis 与 SSE

### 7.1 Redis 职责

Redis 只发布包含租户、用户、会话、Agent 执行、事件/stream sequence 和变化类型的轻量通知。消息内容不包含完整 reasoning，避免敏感内容复制和大消息放大。

通知发布使用数据库 Outbox：业务事务先写 Outbox，发布器成功发送 Redis 后标记已发布。Redis 消息丢失或节点重启时，客户端仍以 PostgreSQL 快照和序号恢复。

### 7.2 两层 SSE

用户登录后维持一条用户级状态 SSE：

```text
GET /api/v1/agent-sessions/activity-events
```

它发送会话级失效通知，用于刷新 queued/running/waiting/done/failed/cancelled、未读和排序版本。重连后前端先重新拉取会话列表，不要求该流永久保存所有通知。

当前打开的会话维持一条详细 SSE：

```text
GET /api/v1/agent-sessions/{conversationSessionId}/events
Last-Event-ID: <session-event-sequence>
```

服务端先重放该序号之后的持久化状态事件，再发送当前 stream 快照，然后注册实时订阅。注册过程使用序号屏障和补查，避免“查询完成、订阅建立之前”出现事件空窗。

切换会话只关闭旧的详细 SSE 并打开新的详细 SSE，不取消后台 Agent 执行；用户级状态 SSE 始终保持。

## 8. REST 与 OpenAPI 契约

保留现有会话创建、列表、详情、消息提交和详细事件路径，并新增：

```text
POST /api/v1/agent-sessions/{conversationSessionId}:mark-read
PUT  /api/v1/agent-sessions:reorder
POST /api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:cancel
POST /api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:retry
GET  /api/v1/agent-sessions/activity-events
```

契约调整：

- `AgentExecution.status` 增加 `queued`。
- Agent 执行 事件类型增加 `execution_queued`；`execution_started` 只表示 queued Agent 执行 已被执行器领取。
- 会话列表/详情增加 `unread`、`lastNotifiableExecutionEventSequence`、`sortOrder` 和 `orderingVersion`。
- Agent 执行 stream 明确包含 `executionAttemptNumber`、`outputDeltaSequence`、reasoning、answer 和 checkpoint 时间。
- 取消响应保留 partial stream，并返回 `cancelled`。
- 重排版本冲突、活动 Agent 执行 冲突、非法状态转换使用不同的稳定 Problem Detail code。

所有公开行为先修改 `contracts/openapi.yaml` 和契约测试，再修改实现。

## 9. 前端设计

### 9.1 会话列表

采用紧凑单行布局：

- 左侧仅在悬停、聚焦或拖拽时突出显示拖拽手柄。
- 中间显示会话标题。
- 右侧显示一个短状态标签或未读圆点。
- 状态标签包括：`排队中`、`思考中`、`待回复`、`待确认`、`失败`、`已取消`。
- 已完成且未读时显示圆点；进入会话后圆点清除。
- 会话状态变化不改变用户顺序。

拖拽完成后乐观更新 UI 并提交完整顺序。409 时恢复服务端顺序并提示“会话顺序已在其他设备更新，请重新排序”。必须同时提供键盘可操作的上移/下移行为，不能只支持鼠标拖拽。

### 9.2 当前会话

- Header 显示 Agent 执行 状态和进度。
- reasoning 区域实时展示模型明确返回的 reasoning，可折叠但默认在运行时展开。
- answer 区域独立流式展示最终回答。
- `queued` 显示排队状态和取消入口。
- `running` 显示取消生成入口。
- `failed` 显示稳定错误摘要和重试入口。
- `cancelled` 保留 partial 内容并显示“回答可能不完整”。
- `waiting_for_user` 和 `waiting_confirmation` 显示对应操作卡片。

## 10. 错误处理

- Ollama 暂时不可用、连接超时或流异常：在最大 attempt 内退避重试；耗尽后进入 `failed`。
- 硬截止时间到期：中止模型流，最终 checkpoint 后进入 `failed`，错误码为 `EXECUTION_DEADLINE_EXCEEDED`。
- 租约丢失：旧 owner 立即停止写入；所有 checkpoint 更新都带 owner、attempt 和 version 条件，防止僵尸实例覆盖新 owner。
- 用户取消：先持久化 `cancellation_requested_at`；owner 观察到后取消 Ollama subscription，执行最终 checkpoint 并写入 `cancelled`。
- 排序版本冲突：返回 409 `SESSION_ORDER_CONFLICT`。
- 同会话已有非终态 Agent 执行：返回 409 `ACTIVE_EXECUTION_CONFLICT`。
- 非法 Agent 执行 状态转换：返回 409 `EXECUTION_STATE_CONFLICT`。
- Redis 不可用：任务和 checkpoint 正常持久化；SSE 客户端通过重连和数据库刷新恢复。

Problem Detail 不包含 reasoning、prompt、回答正文、数据库异常或 Ollama 原始响应。

## 11. 测试策略

### 11.1 持久化与并发

- 迁移测试验证 `queued`、租约字段、attempt 表、用户状态表、外键、租户复合键和部分唯一索引。
- PostgreSQL Testcontainers 并发测试同时启动两个调度实例，为同一用户插入至少 4 个 queued Agent 执行，断言最多 3 个进入 running，剩余 Agent 执行 保持 queued。
- 验证不同用户和不同会话可以并行执行。
- 验证同一会话的 queued/running/waiting 互斥。
- 验证租约过期前不能接管，过期后只有一个新 owner 成功。
- 验证旧 owner/attempt 的 checkpoint 条件更新失败。

### 11.2 Ollama 与状态机

- 使用可控 HTTP Stub 模拟 Ollama reasoning、answer、延迟、断流和错误，不在 CI 依赖真实模型。
- 验证 reasoning 和 answer 被分离、按 checkpoint 持久化并实时发布。
- 验证流中断创建新 attempt、替换实时投影并保留旧 attempt 审计。
- 验证 queued、running、waiting、done、failed、cancelled 的合法和非法转换。
- 验证取消保留 partial 内容并终止模型流。
- 验证最大 attempt 和硬截止时间。

### 11.3 SSE、未读与排序

- 验证详细 SSE 先重放、再发 stream 快照、再实时跟随，序号屏障期间不丢事件。
- 验证 Redis 通知丢失后，重新查询数据库可以恢复状态。
- 验证后台会话进入 done/failed/waiting 后产生未读，reasoning/answer delta 不产生未读。
- 验证 `upToSequence` 不会清除并发到达的新通知。
- 验证拖拽顺序跨设备读取一致，冲突返回 409。
- 验证新会话默认置顶且完整重排会压缩 sort order。

### 11.4 保留策略与前端

- 使用可控 Clock 验证只清空终态且超过 30 天的 reasoning。
- 验证最终回答、Agent 执行、普通事件和未到期 reasoning 不受影响。
- React 测试覆盖紧凑状态、未读圆点、会话切换、用户级与详细 SSE、拖拽/键盘排序、取消、失败重试和 partial 回答提示。
- 完整服务验收使用 PostgreSQL、Redis Testcontainers 和 Ollama HTTP Stub。

## 12. 非目标

- 首版不同时支持其他模型供应商；只实现 Ollama。
- 不展示或推断 Ollama 未显式返回的内部推理。
- 不使用 Redis 作为任务或事件的唯一事实来源。
- 不允许同一会话同时执行多个非终态 Agent 执行。
- 不在本次工作中迁移旧 `data-agent` 的全部工具、技能和 Metric Platform 能力；真实模型执行边界需为后续工具接入保留幂等 checkpoint，但本次只实现当前 V2 会话能力所需的最小范围。
