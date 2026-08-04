# Data Agent V2 以 Content 为中心的对话持久化设计

## 1. 背景

当前 `conversation_message` 同时保存真实用户消息、用户可见的最终回答、internal assistant tool-call 消息和 internal tool observation 消息。工具调用与结果依靠多行消息重建，回答正文与其生成过程不是一个完整持久化对象。V12 又删除了旧 reasoning 列，因此当前数据模型无法表达“某条回答经历了哪些思考和工具步骤”。

本设计把真实问答消息重新定义为对话持久化中心：一条用户提问对应一行，一条 assistant 回答对应一行；回答正文和产生该回答的过程共同保存在该 assistant 消息行中。运行控制表继续承担并发、幂等和恢复职责，但不再作为前端拼装对话消息的数据源。

本设计覆盖此前设计中关于 internal assistant/tool 消息存储形态的描述；会话上下文记忆、执行租约、owner fencing、流式检查点和上下文压缩的其他规则保持不变。

## 2. 目标

- `conversation_message` 只保存真实的 user、assistant 和必要的 system 消息，不再保存独立 tool 消息。
- 每条消息以 `content` 为主体，以有序 `thinkingProcess` 作为与主体绑定的连带过程数据。
- 用户消息和 assistant 消息使用相同结构；用户消息当前保存空过程列表，后续可以按需扩展。
- assistant 执行开始时即可创建占位消息，执行中逐步更新过程，完成时在同一行写入最终回答。
- 每个 ProcessItem 使用 UUID 作为全局唯一标识，并记录开始时间、完成时间和耗时。
- 工具节点完整表达工具标识、调用标识、入参、出参、成功状态、错误和耗时。
- ProcessItem 使用强类型 Java `List` 表达，并以有序 JSONB 数组持久化到 PostgreSQL。
- 单节点正文采用可配置上限；超限时保存有界预览和完整性元数据，但不限制整条 `thinkingProcess` 的总大小。
- 无论节点是否发生数据库截断，都记录经过安全脱敏的完整 ProcessItem 结构化审计日志。
- 保留现有运行控制表，维持工具幂等、多实例接管、崩溃恢复和流式输出能力。

## 3. 非目标

- 不把应用日志作为模型上下文、执行恢复或业务查询的数据源。
- 不在本次设计中引入对象存储、独立 artifact 服务或长期大对象归档。
- 不把历史 thinking 重新注入后续模型上下文。
- 不恢复已经由 V12 删除且没有原始来源的历史 reasoning。
- 不改变工具权限、工具注册和凭据隔离边界。
- 不为 `thinkingProcess` 增加整条 JSONB 的大小上限。

## 4. 已确认的核心规则

### 4.1 Content 是消息主体

`conversation_message` 的一行代表一条真实消息。`content` 保存用户真实提问或 assistant 真实回答，`thinking_process` 保存生成该条消息时产生的过程。前端、REST 和 SSE 均以消息行为读取边界，不再跨 internal 消息或工具表拼装展示内容。

assistant 最终正文生成前允许 `content` 为空。该行是本次执行唯一的回答占位消息；后续 thinking、工具调用、工具结果和最终回答都更新到这一行。

### 4.2 运行控制表继续保留

下列表继续服务运行时，不作为前端对话投影的数据源：

- `agent_execution`：执行生命周期、owner、attempt 和 fencing。
- `model_invocation`：模型调用边界和恢复判断。
- `tool_execution`：工具幂等、状态和失败恢复。
- `agent_execution_stream_checkpoint`：回答流检查点和断线恢复。

`conversation_message` 是对话回放的权威来源；运行控制表是执行正确性的权威来源。二者在事务边界内同步更新。

### 4.3 Thinking 不重新注入模型

`THINKING` 节点用于用户展示、审计和排障，不作为后续模型输入。模型上下文只从 user/assistant `content` 以及工具节点中可恢复的 tool-call/tool-result 协议信息重建。

## 5. 领域模型

### 5.1 公共 ProcessItem 契约

Java 侧使用强类型、多态、有序的 `List<ThinkingProcessItem>`。公共契约包含：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `processItemId` | `UUID` | ProcessItem 全局唯一标识；创建后永不变化 |
| `sequence` | `long` | 当前消息内单调递增的过程序号 |
| `type` | enum | `THINKING` 或 `TOOL` |
| `status` | enum | `PREPARED`、`RUNNING`、`SUCCEEDED` 或 `FAILED`；thinking 不使用 `PREPARED` |
| `createdAt` | `Instant` | 服务端创建节点的时间 |
| `startedAt` | `Instant`，可空 | 实际处理开始时间；尚未调用的 prepared 工具节点为空 |
| `completedAt` | `Instant`，可空 | 节点结束时间；运行中为空 |
| `durationMillis` | `Long`，可空 | `completedAt - startedAt` 的毫秒数；未开始或运行中为空 |
| `schemaVersion` | `int` | 当前节点文档版本，首版固定为 `1` |

反序列化根据 `type` 选择具体 Java 类型。未知 `type`、未知 `schemaVersion`、重复 UUID、重复序号或不符合状态约束的节点均视为持久化数据错误，不得静默丢弃。

### 5.2 ModelThinkingProcessItem

`ModelThinkingProcessItem` 是公共 `ThinkingProcessItem` 接口中 `type=THINKING` 的具体类型，专属信息包括：

| 字段 | 含义 |
| --- | --- |
| `content` | 当前思考段在数据库中保存的完整内容或有界预览 |
| `contentTruncated` | 数据库内容是否被截断 |
| `originalContentSizeBytes` | 安全处理后完整内容的 UTF-8 字节数 |
| `contentSha256` | 安全处理后完整内容的 SHA-256 |
| `modelInvocationSequence` | 产生该思考段的模型调用序号 |
| `modelProvider` | 模型 provider 标识 |
| `modelName` | 模型名称 |

一次模型调用收到第一个 thinking delta 时创建 `RUNNING` 节点。收到工具调用、开始最终回答或模型调用失败时结束该节点并计算耗时。工具调用结束后模型再次思考时创建新的节点，不复用旧节点。

thinking 耗时是服务端从收到首个 thinking delta 到思考段结束的墙钟时间，不宣称等于模型内部不可观测的精确计算时间。

### 5.3 ToolThinkingProcessItem

`ToolThinkingProcessItem` 是公共 `ThinkingProcessItem` 接口中 `type=TOOL` 的具体类型。一次工具调用由一个节点完整表达，不拆成独立 tool-call 和 tool-result 消息。专属信息包括：

| 字段 | 含义 |
| --- | --- |
| `toolId` | 工具注册表中的稳定工具标识；首版使用 `AgentTool.name()` 的稳定值 |
| `toolName` | 工具展示名称 |
| `toolExecutionId` | 服务端工具执行标识 |
| `toolCallId` | 模型 provider 产生的调用标识 |
| `modelInvocationSequence` | 选择该工具的模型调用序号 |
| `input` | 数据库内保存的结构化入参或有界预览文档 |
| `output` | 数据库内保存的结构化出参或有界预览文档 |
| `success` | 工具是否成功；运行中为空 |
| `errorCode` | 失败错误码；成功或运行中为空 |
| `errorMessage` | 经过安全处理的失败信息；成功或运行中为空 |

工具被模型选中时创建 `PREPARED` 节点，立即写入 UUID、创建时间、调用标识、完整性元数据和入参。真正开始调用外部工具前，将同一节点更新为 `RUNNING` 并写入 `startedAt`。工具终态事务继续更新同一节点，补齐出参、成功状态、错误、结束时间和耗时，不创建新的结果节点。

工具耗时从真正开始调用工具的时间计算到取得成功或失败结果的时间，不包含模型生成参数和排队等待时间。

### 5.4 有界载荷文档

结构化工具入参和出参使用统一的有界载荷文档，确保截断后仍是合法 JSON，而不是直接截断 JSON 字符串：

| 字段 | 含义 |
| --- | --- |
| `value` | 未超限时保存的完整结构化 JSON；超限时为空 |
| `preview` | 超限时保存的 UTF-8 安全文本预览；未超限时为空 |
| `truncated` | 是否发生数据库截断 |
| `originalSizeBytes` | 安全处理后完整 JSON 的 UTF-8 字节数 |
| `sha256` | 安全处理后完整 JSON 的 SHA-256 |
| `contentType` | 载荷类型，JSON 首版使用 `application/json` |

截断发生在凭据和敏感字段清理之后。大小和哈希均针对安全处理后的完整载荷计算，禁止用哈希验证或恢复未经安全处理的原始秘密。

## 6. 容量规则

首版配置和默认值如下：

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `data-agent.conversation.process.max-thinking-bytes` | `16384` | 单个 thinking 内容最大持久化字节数 |
| `data-agent.conversation.process.max-tool-input-bytes` | `32768` | 单个工具入参最大持久化字节数 |
| `data-agent.conversation.process.max-tool-output-bytes` | `65536` | 单个工具出参最大持久化字节数 |

限制按安全处理后、规范化序列化后的 UTF-8 字节数执行。超过限制时保留预览、原始大小、SHA-256 和截断标记。

`thinkingProcess` 整体不设置大小上限，不得因累计 JSONB 大小拒绝追加后续节点。节点数量仍由现有 ReAct 限制约束；当前默认单次执行最多 12 次模型调用和 32 次工具调用。若未来调整这些限制，仍不得隐式增加整体大小拒绝逻辑。

## 7. 持久化模型

### 7.1 `conversation_message`

新增：

- `thinking_process jsonb not null default '[]'::jsonb`：有序 ProcessItem 数组。
- 数据库约束确保根节点是 JSON 数组。
- 应用层校验节点版本、类型、UUID、序号、状态、时间和类型专属字段。

保留：

- `content text not null`：真实消息正文；执行中的 assistant 占位消息允许空字符串。
- `role`：只允许真实消息角色。
- `execution_id`：关联触发或产生消息的执行。
- 会话序号、作者、租户、审计和乐观锁字段。

移除 internal 消息停写后不再需要的字段和约束：

- `tool_calls`
- `tool_execution_id`
- `tool_call_id`
- `tool_name`
- `visibility`
- `tool` 消息角色及其形状约束

每个 execution 最多存在一条 assistant 中心消息。该唯一性由数据库索引和应用事务共同保证。

### 7.2 `tool_execution`

`tool_execution` 继续保存运行控制状态。其消息关联调整为：

- 关联本次执行唯一的 assistant 中心消息。
- 新增 `tool_process_item_id UUID`，对应消息 JSONB 内的工具节点 UUID。
- 移除 observation 消息外键和不再存在的 observation 消息标识。

PostgreSQL 无法为 JSONB 数组内元素建立普通外键，因此 `tool_process_item_id` 与节点 UUID 的一致性由同一事务、恢复校验和集成测试保证。

## 8. 写入生命周期

### 8.1 提交用户消息

同一提交事务依次完成：

1. 保存用户消息，`content` 为用户输入，`thinkingProcess=[]`。
2. 创建 `agent_execution` 及既有执行控制记录。
3. 创建本次 execution 唯一的 assistant 占位消息，`content=''`、`thinkingProcess=[]`。
4. 建立用户消息、assistant 消息与 execution 的关联。

前端可以立即取得 assistant 消息 ID，后续所有过程更新都指向该 ID。

### 8.2 Thinking 写入

模型网关恢复对 thinking delta 的类型化输出。执行器在本地合并细粒度 delta，并沿用流式检查点节奏更新当前 `RUNNING` thinking 节点，避免每个 token 重写 JSONB。节点结束时完成最终安全处理、截断元数据、时间和耗时，然后写完整审计日志。

### 8.3 工具调用写入

模型产生工具调用后，在 execution 行锁和 owner fencing 保护下：

1. 为每次调用生成 `processItemId` UUID。
2. 向 assistant 消息追加 `PREPARED` ToolThinkingProcessItem。
3. 创建引用 assistant 消息和 `processItemId` 的 prepared `tool_execution`。
4. 事务提交后才允许真正调用外部工具。

真正调用工具前的独立事务把 `tool_execution` 和对应 ToolThinkingProcessItem 一起推进为 `RUNNING`，并以同一个服务端时间写入节点 `startedAt`。这样工具耗时不包含 prepared 排队时间。

工具结束时在独立短事务中：

1. 重新校验 execution owner、attempt 和 `tool_execution` 版本。
2. 更新原 Tool ProcessItem 的出参、成功状态、错误、完成时间和耗时。
3. 将 `tool_execution` 推进为终态。
4. 提交成功后记录一次完整结构化审计日志并发布消息更新。

节点更新和工具终态必须原子提交，不允许出现工具已终态但消息节点仍为 `RUNNING` 的持久状态。

### 8.4 最终回答写入

模型完成最终回答时，既有流检查点先收敛全部 answer delta。完成事务将完整回答写入同一 assistant 消息的 `content`，完成模型调用、execution、attempt 和生命周期事件。禁止另行插入新的用户可见 assistant 消息。

### 8.5 并发与恢复

所有 ProcessItem 追加和更新都必须在 execution owner、attempt fencing 及消息乐观锁保护下执行。旧 worker 不能覆盖新 owner 的节点或正文。

恢复器按 UUID 和 `tool_execution.tool_process_item_id` 识别已有工具节点：

- 已完成工具不得再次调用。
- `RUNNING` 节点按既有幂等和恢复规则继续或失败收敛，不重复创建 UUID。
- 恢复后新增节点继续使用更大的消息内 `sequence`。
- 超限工具结果只从数据库保存的有界载荷恢复；审计日志不是运行时恢复来源。

执行失败或取消时，事务必须把无法继续的 `PREPARED` 或 `RUNNING` 节点收敛为 `FAILED`，写入 `completedAt` 和安全错误信息，并按完整审计日志规则输出该节点详情。已经写入 `startedAt` 的节点同时计算 `durationMillis`；从未实际开始的 prepared 工具节点保留空 `startedAt` 和空 `durationMillis`，避免伪造工具执行耗时。只有仍被当前 owner 合法处理的节点可以保持非终态。

## 9. 模型上下文重建

`ConversationContextLoader` 按会话消息序号读取真实消息：

- user 消息直接转换为 user model message。
- assistant 消息按 ProcessItem `sequence` 读取 Tool ProcessItem。
- 根据 `modelInvocationSequence`、`toolCallId`、入参和出参重建 provider 需要的 assistant tool-call 与 tool-result 消息。
- 最后追加非空 assistant `content`。
- `THINKING` 节点不进入模型输入。

当前执行未完成时，同一 assistant 占位消息也参与恢复。若工具载荷因单节点上限被截断，恢复上下文使用持久化的有界载荷，不读取日志，也不重新执行已经终态的工具。

## 10. REST、SSE 与前端投影

REST 和 SSE 的 MessageView 增加 `thinkingProcess`，用户消息固定返回空数组而不是 `null`。当前工作区正在调整的 `session_snapshot` 和 `execution_update` 继续作为会话流协议，但其中消息始终包含同一行的 `content + thinkingProcess`。

服务端必须先持久化 ProcessItem，再发布 SSE。断线重连以 PostgreSQL 中的完整消息投影覆盖本地状态。

前端按 assistant 消息展示：

- `content` 为正式回答正文。
- `thinkingProcess` 为该回答下可展开的有序时间线。
- thinking 节点展示思考内容、模型、起止时间、耗时和状态。
- tool 节点展示工具标识、调用标识、数据库内入参/出参、截断信息、成功状态、错误、起止时间和耗时。
- 用户消息的空过程列表不渲染过程区域。
- 执行中的 assistant 占位消息可以展示已有过程和回答流；完成后仍更新同一消息组件。

前端使用 `processItemId` 作为节点 key 和幂等归并标识，不使用数组下标代替业务身份。

### 10.1 当前会话使用定向 SSE，后台会话使用轮询

前端不订阅用户级 activity SSE，也不为后台会话建立长连接。连接和轮询生命周期按以下规则收敛：

- `queued`、`running`、`waiting_for_user`、`waiting_confirmation` 视为仍在处理中的状态；`idle`、`done`、`cancelled`、`failed`、`lost` 视为非处理状态。
- 唯一允许建立的长连接是当前页面正在查看且仍在处理的会话详情 SSE。请求路径必须携带该 `conversationSessionId`，服务端事件体也必须携带同一个 `conversationSessionId`；前端拒绝归并 sessionId 不匹配的事件。
- 切换当前会话时先关闭旧 sessionId 的连接。新页面先通过 REST 读取会话详情；仅当详情确认仍在处理时，才为新 sessionId 建立 SSE。
- 打开空闲或历史终态会话只执行一次 REST 详情查询，不建立 SSE。提交消息或重试成功后，REST 响应把当前会话推进到活跃状态，再触发该 sessionId 的详情连接建立。
- 收到终态 `execution_update` 时先校验 sessionId，并归并该事件携带的最终消息、`content` 和 `thinkingProcess`，随后立即关闭该 sessionId 的 SSE。终态更新与中心消息在服务端同一事务提交，因此关闭连接不得造成最终回答丢失。
- 当前会话 SSE 异常后只对同一 sessionId 执行一次 REST 详情同步；同步结果仍在处理时可以重新建立该 sessionId 的连接，已经终态时保持关闭。
- 当前页面之外的处理中会话只通过会话摘要 REST 接口每 60 秒轮询一次。轮询只更新状态、未读、排序和更新时间等轻量字段，不得覆盖当前页面已经由详情 REST/SSE 持有的消息、回答或 `thinkingProcess`。
- 后台轮询发现会话进入终态后更新侧边栏状态；用户之后打开该会话时，再通过 REST 详情接口读取最终正文和完整过程。
- 已知后台处理中会话为零时停止分钟轮询；出现新的后台执行（例如当前会话切换到后台或本页面提交/重试后离开）时重新启动。页面卸载时清理定时器。

任意时刻单个浏览器页面最多只有一条、且明确绑定当前 `conversationSessionId` 的详情 SSE。当前会话不在处理中时占用零条长连接；后台处理状态通过 60 秒 REST 轮询最终收敛。

## 11. 完整审计日志

### 11.1 记录规则

数据库是否发生截断不影响日志记录。每个 ProcessItem 结束时都向独立结构化 logger 写一次完整详情：

- 公共字段：`processItemId`、类型、序号、状态、创建时间、开始时间、完成时间和耗时。
- thinking 节点：安全处理后的完整思考内容、模型调用序号、provider 和模型名称。
- tool 节点：安全处理后的完整工具入参、完整工具出参、工具标识、执行标识、provider 调用标识、成功状态、错误和耗时。

建议 logger 名称为 `agent.conversation.process.audit`。日志事件必须使用 UUID 与数据库节点关联。thinking 在节点结束时记录一次完整内容；tool 在终态时记录一次完整调用结果，避免流式检查点重复打印大段载荷。

### 11.2 必须保留的代码注释

完整 ProcessItem 日志代码旁必须写明：

> 按主管审计要求记录经安全脱敏后的完整 ProcessItem 详情和工具调用结果，便于后续审计排查；不得根据数据库是否截断跳过该日志。

该注释用于防止维护者把完整审计日志误认为普通调试日志而删除。

### 11.3 安全边界

“完整”是安全脱敏后的完整业务载荷。服务端执行上下文、访问令牌、密码、Cookie、连接凭据和其他受保护秘密不得进入消息或日志。安全清理发生在大小计算、哈希、数据库截断和日志输出之前。

专用 logger 便于部署环境单独设置访问权限、采集路由和保留周期。应用层不主动截断该 logger 的完整详情，但日志平台可能存在单条事件限制，因此日志不承担恢复或长期业务归档职责。

## 12. 数据迁移

使用下一个可用 Flyway 版本；实施计划开始前重新确认版本号，不修改已经执行的迁移。

迁移步骤：

1. 为 `conversation_message` 增加 `thinking_process` 和数组约束。
2. 按 `tenant_id`、`execution_id` 和原消息序号识别每次执行的公开 assistant 回答及 internal assistant/tool 消息。
3. 对已有公开回答，把 internal tool-call 和 observation 转换为有序 Tool ProcessItem 并写入该回答行。
4. 对尚未产生公开回答的活动执行，复用已有 internal assistant 行作为中心占位消息；没有任何 internal assistant 行时由恢复路径创建唯一占位消息。
5. 为迁移产生的 ProcessItem 生成 UUID，并更新 `tool_execution` 指向中心消息及对应 `tool_process_item_id`。
6. 校验每个工具执行均能定位唯一中心消息和唯一工具节点。
7. 删除已经聚合的 internal 消息，移除 observation 外键、internal 专用消息列和旧形状约束。
8. 收紧真实消息角色和每 execution 一条 assistant 消息的约束。

历史 reasoning 已被 V12 删除，迁移不得生成占位 thinking 或猜测历史内容。迁移后用户消息保持空列表。

## 13. 异常处理

- JSONB 序列化失败：当前事务回滚，不发布 SSE 或审计完成事件。
- 未知节点类型或版本：读取失败并输出明确持久化错误，不返回空列表掩盖损坏。
- UUID、序号或类型字段冲突：拒绝写入并回滚。
- owner、attempt 或乐观锁不匹配：抛出执行所有权丢失错误，旧 worker 停止写入。
- 工具节点更新成功但工具终态失败：同一事务回滚，二者保持原状态。
- 完整审计日志输出失败：不得回滚已经完成的工具业务事务；记录独立的审计日志投递失败告警，避免因日志系统故障重复执行有副作用的工具。
- 失败或取消的 execution 保留 assistant 占位消息及已经产生的 ProcessItem，供界面和审计查看；不得伪造最终回答正文。

## 14. 验证策略

### 14.1 领域与持久化测试

- 多态 `List<ThinkingProcessItem>` 与 PostgreSQL JSONB 往返一致。
- 用户消息空列表、assistant 占位消息和最终正文更新。
- UUID 唯一、序号稳定、时间与耗时约束。
- thinking、工具入参和工具出参分别在边界内、刚好达到边界和超过边界时的行为。
- 超限预览保持合法 JSON 文档，原始字节数和 SHA-256 正确。
- 不存在 `thinkingProcess` 整体大小拒绝逻辑。

### 14.2 ReAct 与恢复测试

- 多轮 thinking、多个工具调用和最终回答始终更新同一 assistant 消息行。
- 工具成功、业务失败、超时和未知工具都完成原工具节点。
- 工具节点和 `tool_execution` 在同一事务中达到一致终态。
- takeover 和 retry 不重复创建 UUID，不重复调用已终态工具。
- 上下文从 Tool ProcessItem 按原顺序重建，thinking 不进入模型输入。
- 截断工具结果的恢复只使用数据库有界载荷，不读取日志。

### 14.3 审计日志测试

- 未截断节点也输出完整结构化日志。
- 截断节点的日志仍包含安全处理后的完整详情。
- 日志与数据库使用相同 `processItemId`。
- thinking 和 tool 每个终态节点各输出一次完整详情。
- 凭据、令牌、Cookie 和连接秘密被清理。
- 审计 logger 故障不会导致已完成工具被重复执行。

### 14.4 契约与前端测试

- REST、`session_snapshot` 和 `execution_update` 返回相同消息结构。
- 用户消息返回 `thinkingProcess: []`。
- 前端将过程绑定到正确的 assistant 消息并以 UUID 幂等归并。
- 每个节点展示状态、时间和耗时；工具节点展示完整性与截断元数据。
- SSE 断线重连后以数据库快照恢复相同内容。

### 14.5 迁移测试

- 历史公开回答和 internal 工具消息正确聚合。
- 活动执行得到唯一中心 assistant 消息。
- `tool_execution` 正确关联迁移后的 UUID 节点。
- internal 消息和旧字段清理后不存在孤立外键。
- 已被删除的历史 reasoning 不被伪造。

## 15. 完成标准

- 一次真实问答在 `conversation_message` 中严格表现为一条 user 行和一条 assistant 行。
- assistant 行的 `content` 与有序 `thinkingProcess` 共同构成完整对话持久化对象。
- 不再生成 internal assistant/tool 消息。
- 每个 ProcessItem 具有 UUID、时间、耗时、状态及类型专属详情。
- 工具节点包含工具标识、调用标识、入参、出参、成功状态和错误信息。
- 单节点超限时数据库有界截断，整条 `thinkingProcess` 不受总大小限制。
- 所有终态 ProcessItem 无论是否截断都输出安全脱敏后的完整结构化审计日志，并保留主管审计要求注释。
- 运行控制、崩溃恢复、上下文重建、REST/SSE 和前端展示均通过对应测试。
