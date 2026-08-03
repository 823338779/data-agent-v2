# Data Agent V2 会话上下文记忆设计

## 1. 背景

Data Agent V2 已经将消息按 `conversation_session_id` 和单调递增的
`conversation_message_sequence` 持久化，但模型上下文读取仍通过当前
`execution_id` 过滤。因此，同一次 ReAct 执行内的 user、assistant tool call、tool
observation 和最终回答可以被重放，新的 execution 却看不到同一会话中此前 execution
产生的消息。

本设计为交互式会话增加持久、可恢复、受上下文预算约束的会话级记忆。原始消息继续作为
事实源；当长期历史接近模型窗口时，系统生成滚动摘要，同时尽量保留最近若干个完整问答的
原文。

## 2. 目标

- 同一会话的新一轮 execution 能读取此前所有 execution 的相关上下文。
- 历史上下文包含 user、assistant、assistant tool calls、tool observations 和最终回答。
- 原始消息不因压缩而修改、覆盖或删除。
- 使用持久化滚动摘要控制长会话输入体积。
- 正常情况下完整保留最近 5 个已完成问答，且保留数量可通过配置调整。
- 压缩、进程恢复、多实例接管和执行 retry 具有确定且可审计的行为。
- 不改变现有会话 REST 路径和响应结构。

## 3. 非目标

- 不实现跨会话、跨用户或跨租户的长期记忆。
- 不增加向量数据库、embedding 检索或语义记忆召回。
- 不支持除当前 Ollama `qwen3.5:9b` 之外的模型预算自适应。
- 不删除或归档被摘要覆盖的原始消息。
- 不向前端暴露摘要正文，也不允许日志或 SSE 携带摘要、prompt 或 tool observation 正文。

## 4. 已确认的产品规则

### 4.1 模型窗口

- Ollama 请求显式设置 `num_ctx=65536`。
- 正常回答最多使用 4096 个输出 token。
- 输入预算固定为 57344 token（56K），并保留 4096 token（4K）的额外安全余量。
- 64K 窗口及其输入、输出和余量分配绑定当前 `qwen3.5:9b`，本次不做通用模型配置。

### 4.2 历史内容

- 未压缩历史完整保留 user、assistant、assistant tool calls 和 tool observations。
- assistant tool call 与其全部 tool observations 是不可拆分的原子组，必须一起保留或一起压缩。
- reasoning 不属于会话记忆输入；只有持久化的消息正文和工具协议消息参与上下文组装。

### 4.3 最近问答保护

- `data-agent.context.recent-completed-turns` 控制正常情况下不压缩的最近完整问答数，默认值为
  `5`，对应环境变量为 `DATA_AGENT_CONTEXT_RECENT_COMPLETED_TURNS`。
- 配置值必须是正整数，非法值导致应用启动失败。
- 如果受保护问答加当前轮已经超过输入预算，系统从保护区中最老的完整问答开始继续压缩，
  直到上下文可放入窗口。
- 当前尚未完成的一轮不压缩；如果当前轮本身无法放入输入预算，execution 以
  `CONTEXT_WINDOW_EXCEEDED` 失败。

### 4.4 压缩失败

- 每个已选压缩批次最多进行 2 次摘要模型调用尝试。重新估算后仍超限而启动的下一批压缩不
  计作前一批的重试。
- 两次均失败时不静默遗忘旧历史，不降级为只使用最近问答。
- execution 写入 `context_compaction_failed` 后以 `CONTEXT_COMPACTION_FAILED` 失败。
- 最后一份有效摘要和所有原始消息保持不变，用户可以使用现有 execution retry 重新执行。

## 5. 方案选择

采用独立的会话摘要检查点，而不是把摘要写成 internal system 消息或在每次请求时临时生成。

独立检查点具有明确的覆盖边界，不污染原始消息序号和 tool call 配对关系；成功摘要可以原子
替换旧摘要，失败时保留上一份有效结果，也符合当前 PostgreSQL 事实源和多实例 lease fencing
架构。

## 6. 组件边界

### 6.1 `agent-domain`

新增会话上下文摘要领域模型，表达：

- 摘要业务标识。
- 所属会话。
- 版本化摘要文档。
- 摘要已经覆盖到的最大会话消息序号。
- 摘要估算 token 数。
- 乐观锁版本。

### 6.2 `agent-persistence`

新增 Flyway 迁移、Entity、Mapper 和基于 `ServiceImpl` 的持久化服务。其他模块仍不得直接
引用 Mapper 或 MyBatis API。

调整消息查询，使模型上下文可以按会话和序号范围读取，而不是按单个 execution 读取。工具
执行查询必须支持校验本次原始消息集合涉及的多个 execution。

### 6.3 `agent-web`

- `ConversationContextLoader`：按会话边界重建模型消息并校验 tool call/observation 配对。
- `ConversationTurnGrouper`：把原始消息划分为完整问答和当前未完成问答。
- `ConversationTokenEstimator`：保守估算消息、tool definitions 和 Ollama 协议开销。
- `ConversationContextCompactionService`：选择压缩范围、调用摘要模型、验证摘要并提交检查点。
- `ConversationContextAssembler`：协调摘要、原始消息、预算和最终模型输入。
- `OllamaAgentModelGateway`：保留正常流式调用，并增加不携带 tools 的摘要调用。

这些组件保持单一职责。上下文组装不负责数据库映射，摘要服务不负责 ReAct 工具执行，模型
网关不决定压缩边界。

## 7. 数据模型

实现时使用下一个可用的 Flyway 版本创建 `conversation_context_summary`。按当前迁移序列应为
V13；执行计划开始前仍需重新确认迁移编号，且不得覆盖或改写既有迁移。

建议字段如下：

| 字段 | 含义 |
| --- | --- |
| `id bigint generated always as identity` | 数据库主键 |
| `conversation_context_summary_id varchar(64)` | 摘要业务唯一标识 |
| `tenant_id varchar(64)` | 租户隔离键 |
| `conversation_session_id varchar(64)` | 所属会话业务标识 |
| `summary_document jsonb` | 版本化结构摘要 |
| `summarized_up_to_conversation_message_sequence bigint` | 已覆盖的最大消息序号 |
| `estimated_token_count integer` | 摘要的保守估算 token 数 |
| 审计、逻辑删除和 `version` 字段 | 与现有租户审计实体一致 |

数据库约束：

- `conversation_context_summary_id` 全局唯一。
- `(tenant_id, conversation_session_id)` 在未逻辑删除记录中唯一。
- 会话外键使用 `(tenant_id, conversation_session_id)`，禁止跨租户引用。
- 覆盖序号和估算 token 数不得为负数。
- 摘要文档不得为空，且必须由应用层验证文档版本。

每个会话只保留一份当前有效摘要。更新采用乐观锁；只有摘要模型成功返回且通过结构校验后
才更新该行。

## 8. 摘要文档

摘要使用版本化 JSON 文档，首版 `version=1`。至少包含以下数组：

- `confirmedFacts`：已经确认且后续仍有意义的事实。
- `userPreferences`：用户在会话中明确表达的偏好和限制。
- `decisions`：已作出的业务或分析决策及理由。
- `toolInteractions`：工具名称、关键输入语义、结果、失败码和失败信息。
- `openQuestions`：尚未解决的问题、歧义和待办。

摘要提示必须要求模型：

- 合并旧摘要与新增历史，不臆造原文没有的信息。
- 保留标识符、时间范围、指标口径、过滤条件、单位和工具失败信息。
- 删除已经被后续消息明确推翻的旧结论，但记录最终结论。
- 不输出 reasoning 或分析过程。
- 只输出符合固定结构的 JSON。

应用层验证文档版本、必需字段、字段类型和最大摘要体积。摘要文档估算值不得超过 4096
token；结构不合法或摘要过大均视为一次可重试的压缩失败。

## 9. 完整问答定义

一个问答从一条 user 消息开始，到下一条 user 消息之前结束，包含该区间内所有 assistant 和
tool 消息。

- 区间内存在用户可见的最终 assistant 回答时，该问答视为已完成并参与最近 N 轮保护计数。
- 失败 execution 之后的手动 retry 继续引用同一 triggering user message，因此原 execution 和
  retry 产生的 tool/assistant 消息属于同一个问答。
- 没有最终 assistant 回答的最后一个区间视为当前未完成问答，不参与最近 N 轮计数，也不被
  压缩。
- system 消息不单独构成问答；如果未来存在原始 system 消息，应作为全局前缀保留，并计入
  输入预算。

## 10. 上下文组装与压缩算法

每次正常模型调用前执行以下流程：

1. 使用现有 `inputUpToConversationMessageSequence` 固定本次可读取的最大消息序号。
2. 读取当前会话摘要；没有摘要时，覆盖序号视为 0。
3. 按会话读取 `(摘要覆盖序号, 输入边界]` 内的全部原始消息并按消息序号升序排列。
4. 重建跨 execution 的 assistant tool calls 和 tool observations，并验证引用关系。
5. 按完整问答分组，识别最近配置数量的已完成问答和当前未完成问答。
6. 估算摘要 system message、原始消息、tool definitions 和协议开销的总输入 token。
7. 未超过 57344 token 输入预算时直接返回模型输入。
8. 超限时，从保护区之前最老的完整问答开始选择连续压缩范围。摘要输入由旧摘要和新选中的
   原始问答组成。
9. 摘要输入本身也必须满足 57344 token 预算；选择器应缩小连续批次而不是截断问答。如果旧
   摘要加一个不可拆分问答仍无法进入摘要窗口，则以 `CONTEXT_WINDOW_EXCEEDED` 终止。
10. 摘要成功提交后重新读取或使用已提交检查点，仅发送新摘要和新边界之后的原始消息。
11. 如果仍然超限，继续压缩下一批最老问答；每个批次分别拥有最多 2 次生成尝试。
12. 如果没有非保护问答可压缩，则从保护区最老问答开始继续压缩。
13. 如果只剩当前未完成问答仍超限，以 `CONTEXT_WINDOW_EXCEEDED` 终止。

发送给正常模型的最终顺序为：

```text
[system: 版本化长期会话摘要]
[摘要边界之后的原始 user/assistant/tool 消息]
[当前 user 消息及当前 ReAct 工具链]
```

没有摘要时不插入空的记忆 system message。

## 11. Token 估算

Ollama 的稳定公开 API 只在模型调用响应中返回实际 `prompt_eval_count`，本次不依赖实验性的
`/api/tokenize`，也不引入新的 Qwen tokenizer 依赖。

`ConversationTokenEstimator` 使用针对当前文本场景的保守规则：

- CJK 字符按不低于 1 token 估算。
- 拉丁文本按字符数保守折算。
- JSON 标点、字段名、tool call ID、role 和消息边界加入固定开销。
- tool definitions 必须参与估算。
- 估算结果不得利用 4K 安全余量继续塞入业务输入。

Ollama 最终响应中的真实 `prompt_eval_count` 写入结构化指标或日志，但不记录 prompt 内容。
测试使用固定的中英文、JSON 和工具协议样本校验估算结果，并观测估算值与真实值的偏差。

## 12. 事务、并发与恢复

摘要模型调用期间不得持有数据库事务。

### 12.1 开始压缩

短事务执行以下操作：

- 校验当前 execution 的 owner、attempt、lease 和截止时间。
- 固定旧摘要版本、旧覆盖边界和目标新覆盖边界。
- 分配会话级事件序号并持久化 `context_compaction_started`。

事件负载只包含摘要版本、边界和估算 token 数，不包含消息或摘要正文。

### 12.2 生成摘要

事务外调用 Ollama。摘要调用：

- 使用 `qwen3.5:9b` 和 `num_ctx=65536`。
- 不传递 tools。
- 不向用户 SSE 发送 reasoning 或 answer delta。
- 受 execution heartbeat、取消和硬截止时间约束。
- 最多调用 2 次。

### 12.3 提交摘要

另一个短事务重新校验 owner 和 attempt，并使用旧摘要版本执行 compare-and-set。成功后写入
`context_compaction_completed`。如果 lease 已被接管或版本不匹配，丢弃模型返回结果，旧
owner 不得覆盖新 owner 的摘要。

### 12.4 失败和崩溃

- 两次摘要调用失败或结构始终非法：写入 `context_compaction_failed`，再以
  `CONTEXT_COMPACTION_FAILED` 终止 execution。
- 当前轮单独超限：以 `CONTEXT_WINDOW_EXCEEDED` 终止 execution。
- 进程在摘要调用中崩溃：有效摘要未改变；新 owner 接管后按相同消息边界重新计算和压缩。
- 用户取消：中断摘要调用，不提交临时结果，并沿用现有 cancelled 收敛流程。
- execution retry：重新进行预算检查和压缩，不复用失败的临时模型输出。

## 13. 事件与外部契约

在 `ExecutionEventType` 中实现契约已经允许的事件：

- `context_compaction_started`
- `context_compaction_completed`
- `context_compaction_failed`

这些事件写入现有 `agent_execution_event`，通过 PostgreSQL 重放、Redis 低延迟通知和 SSE
reconciliation 传播。Redis 仍不是事实源。

REST 路径和响应结构不变。已有 OpenAPI 和 JSON Schema 已列出三个事件类型；实现时只需确认
事件负载满足现有通用 payload 约束，不扩大公开 API。

## 14. 错误处理

新增稳定失败码：

- `CONTEXT_COMPACTION_FAILED`：摘要提供方调用失败或摘要结构无效且重试耗尽。
- `CONTEXT_WINDOW_EXCEEDED`：无法在不压缩当前未完成问答的前提下满足输入预算。

Problem Detail、execution failure detail、日志和指标不得包含原始消息、摘要、tool 参数或 tool
observation 正文。只有内部异常类型、边界、计数、耗时和稳定失败码可以被记录。

## 15. 测试策略

### 15.1 `agent-persistence`

- Flyway 迁移验证表、复合外键、唯一约束、非负约束和逻辑删除条件。
- 验证摘要查询受租户隔离。
- 验证乐观锁拒绝旧版本覆盖。
- 验证按会话和序号范围读取消息，不再依赖单个 execution。

### 15.2 `agent-web` 单元测试

- 中英文、混合文本、JSON 和 tool definitions 的 token 估算。
- 完整问答分组，包括失败 execution 后 retry 的情况。
- assistant tool call 与 observations 不可拆分。
- 默认保护最近 5 个完整问答。
- 修改 `recent-completed-turns` 后保护边界正确变化。
- 非保护历史优先压缩；保护区自身超限时从最老保护问答开始降级压缩。
- 当前轮单独超限时返回稳定失败。
- 摘要文档结构校验和最大体积校验。
- 第一次摘要失败、第二次成功，以及两次均失败的行为。

### 15.3 上下文与模型网关测试

- 第二轮模型输入包含第一轮来自不同 execution 的完整消息和工具链。
- 摘要边界之前的原始消息不再重复发送。
- 摘要边界之后的 tool call/observation 仍严格配对。
- 正常和摘要 Ollama 请求都设置 `num_ctx=65536`。
- 摘要请求不携带 tools，不向正常回答流发布增量。
- 最终响应的 `prompt_eval_count` 只进入安全的指标或日志。

### 15.4 `agent-server` 集成测试

- 同一会话多轮记忆；不同会话和不同租户相互隔离。
- 滚动压缩后仍能引用早期摘要事实和最近原文。
- 压缩开始、完成、失败事件可通过 PostgreSQL 重放和 SSE reconciliation 收敛。
- Redis 不可用不影响摘要提交和最终状态。
- 进程接管场景中旧 owner 无法提交摘要，新 owner 可以重新压缩。
- 取消、硬截止时间、摘要失败和 execution retry 的状态收敛。

### 15.5 验证顺序

先运行受影响类和模块的最小测试，再执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

## 16. 验收条件

满足以下条件后视为实现完成：

1. 同一会话的新 execution 能获得此前 execution 的完整未压缩上下文。
2. 超过预算后使用持久化摘要和摘要边界之后的原始消息，而不是静默截断历史。
3. 默认最近 5 个完整问答保持原文，配置调整和保护区降级均有自动化测试。
4. 跨 execution 的 tool call/observation 顺序和引用关系保持有效。
5. 压缩失败、当前轮超限、取消和多实例接管均进入明确且可恢复的终态。
6. 原始消息不被修改，摘要正文不进入公开接口或日志。
7. 受影响模块测试和后端完整 `mvn verify` 通过。
