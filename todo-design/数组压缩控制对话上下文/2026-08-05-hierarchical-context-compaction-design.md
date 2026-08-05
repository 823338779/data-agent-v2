# Data Agent V2 分层上下文压缩设计

## 1. 背景与适用范围

Data Agent V2 已经按会话持久化 `message` 和每条 assistant 消息的
`thinkingProcess`，但当前模型调用仍会按消息上界重建全部历史。随着会话增长，模型输入会
逐渐逼近上下文窗口，最终导致输入失败或不得不无规则地丢弃历史。

本设计在不改变系统提示词、工具定义和原始会话事实源的前提下，引入三层会话记忆：

- Hot：最近 10 个完整问答的原始内容。
- Warm：最近 100 个完整问答中除 Hot 之外的二进制摘要树。
- Cold：100 个完整问答之前的滚动摘要检查点。

本设计替代
`docs/superpowers/specs/2026-08-03-session-context-memory-design.md` 中关于压缩触发水位、最近
问答保护数量、摘要持久化结构和压缩算法的规则。原设计中的会话级记忆、租户隔离、原始消息
事实源、execution lease、owner fencing、流式恢复和安全日志规则继续有效。

## 2. 目标

- 使用模型上下文配置预估完整输入，在占用超过 75% 时进入压缩流程。
- 压缩完成后把预计输入占用降到不高于上下文窗口的 70%。
- 正常情况下完整保留最近 10 个已完成问答和当前未完成问答。
- 使用二进制摘要树为最近历史提供可按预算切换的摘要粒度。
- 使用单一滚动摘要和单调前进的指针约束长期历史体积。
- 摘要可以读取历史 `message` 和全部 `thinkingProcess`，但只持久化结构化、可复用的思路状态。
- system prompt、工具定义和模型协议框架始终按原始配置发送，不进入摘要、不被摘要替换。
- 原始消息和 `thinkingProcess` 永不因压缩被修改或删除。
- 压缩在进程恢复、多实例接管、execution retry 和用户取消场景下确定收敛。

## 3. 非目标

- 不实现跨会话、跨用户或跨租户的长期记忆。
- 不增加 embedding、向量检索或基于当前问题挑选历史片段的语义召回。
- 不实现可以进行前缀差分的数学 Fenwick Tree；LLM 摘要不可相减。
- 不删除或归档摘要覆盖的原始消息和思考过程。
- 不向 REST、SSE、日志或指标暴露摘要、message、thinking 或工具载荷正文。
- 不允许摘要动态修改 system prompt、工具定义、权限、租户或执行策略。

## 4. 核心术语

### 4.1 完整问答

一个问答从一条 user 消息开始，到下一条 user 消息之前结束，包含该区间内的 assistant 消息、
`thinkingProcess`、tool call 和 tool observation。

- 区间内存在用户可见的最终 assistant 回答时，该问答才算完成。
- 失败 execution 之后对同一 triggering user message 的 retry 仍属于同一个问答。
- 最后一个没有最终 assistant 回答的区间是当前未完成问答，不参与最近 N 轮计数。
- tool call 与对应 observations 是不可拆分的原子组。

### 4.2 压缩检查点

Cold 层保存一份当前有效滚动摘要和
`summarized_up_to_conversation_message_sequence`。该指针表示其之前及自身的会话历史已经被
Cold 摘要覆盖。摘要和指针必须原子更新，指针只能向前移动。

### 4.3 二进制摘要节点

Warm 层节点覆盖连续的完整问答，层级与覆盖数量满足：

```text
level 0 = 1 个完整问答
level 1 = 2 个完整问答
level 2 = 4 个完整问答
level n = 2^n 个完整问答
```

父节点只能由两个相邻、同层、版本兼容的子节点按“左节点在前、右节点在后”的固定顺序
生成。该结构借用 Fenwick Tree 和二进制进位的前缀聚合思想，但不提供差分查询。

## 5. 上下文预算

### 5.1 配置

新增或统一以下配置：

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `data-agent.context.window-tokens` | `65536` | 模型上下文窗口 `W` |
| `data-agent.context.compaction-trigger-ratio` | `0.75` | 压缩软触发比例 |
| `data-agent.context.compaction-target-ratio` | `0.70` | 压缩完成后的目标比例 |
| `data-agent.context.recent-raw-turns` | `10` | Hot 层正常保护轮数 |
| `data-agent.context.warm-window-turns` | `100` | Hot 与 Warm 合计的近期窗口 |
| `data-agent.context.safety-reserve-tokens` | `4096` | 输入与输出之外的安全余量 |

模型现有 `maxOutputTokens` 继续作为输出预留。配置必须满足：

```text
0 < targetRatio < triggerRatio < 1
recentRawTurns > 0
warmWindowTurns > recentRawTurns
hardInputLimit > softTrigger
```

非法配置导致应用启动失败，不在运行时静默修正。

### 5.2 水位计算

```text
softTrigger   = floor(W * 0.75)
targetBudget  = floor(W * 0.70)
hardInputLimit = W - maxOutputTokens - safetyReserveTokens
```

以 `W=65536`、`maxOutputTokens=4096`、`safetyReserveTokens=4096` 为例：

```text
softTrigger    = 49152
targetBudget   = 45875
hardInputLimit = 57344
```

### 5.3 完整输入估算

每次正常模型调用前估算：

```text
estimatedInputTokens =
    systemPromptTokens
  + toolDefinitionTokens
  + protocolOverheadTokens
  + coldSummaryTokens
  + selectedWarmNodeTokens
  + hotRawTurnTokens
  + currentIncompleteTurnTokens
```

system prompt、工具定义、role、消息边界和其他协议开销参与预算计算，但不属于压缩候选。

`ConversationTokenEstimator` 优先使用与模型一致且稳定的 tokenizer。当前 provider 无稳定
tokenize 能力时，使用保守估算规则覆盖 CJK、拉丁文本、JSON、tool call ID、role、消息边界和
工具定义。正常模型响应中的真实 `prompt_eval_count` 只用于安全指标和误差校准，不记录输入
正文，也不替代调用前估算。

## 6. 三层会话记忆

完成问答总数为 `N` 时，压缩周期的目标逻辑边界为：

```text
Cold：第 1 ～ N-100 轮
Warm：第 N-99 ～ N-10 轮
Hot： 第 N-9 ～ N 轮
```

不足相应轮数时区间自然缩短。最近 100 轮由 Warm 摘要树和 Hot 原文共同构成。

压缩只在预计输入超过 75% 时启动。因此两个压缩周期之间，新增问答可以暂时作为检查点之后的
增量历史存在；系统不为了机械移动 100 轮边界而每轮调用摘要模型。下一个压缩周期会把已经
完整移出最近 100 轮的连续节点统一推进到 Cold。

### 6.1 Hot

Hot 正常保留最近 10 个完整问答的：

- user 和 assistant 消息原文；
- assistant thinking 原文；
- tool call、输入、输出和失败信息；
- 原有 tool call/observation 配对关系。

当前未完成问答也完整保留且永不压缩。

普通 thinking 通过内部 `ModelInputMessage` 的 assistant thinking/history 字段传递，由模型网关
映射到 provider 支持的历史思考字段。不得把 thinking 拼接到 system prompt。provider 不支持
历史 thinking 时必须返回明确的模型能力配置错误，不能静默改变消息角色或系统规则。

### 6.2 Warm

Warm 使用不可变的二进制摘要节点。每次上下文组装选择一组满足以下条件的节点：

- 完整覆盖目标 Warm 区间；
- 节点相互不重叠且没有缺口；
- 父节点和其任一子孙节点不能同时出现；
- 在预算允许时，较新的历史优先使用更细的节点；
- 预算紧张时，从最老的连续历史开始用父节点替换子节点。

例如同一段历史可以表示为五个详细子节点，也可以表示为一个更紧凑的父节点。选择过程只读取
已经生成的节点，不调用 LLM。

### 6.3 Cold

Cold 只保留一份当前有效滚动摘要。当完整 Warm 节点已经移出最近 100 轮时，使用：

```text
旧 Cold 摘要 + 按区间升序排列的待迁移 Warm 节点 -> 新 Cold 摘要
```

成功后将 Cold 指针移动到最后一个被吸收节点的
`end_conversation_message_sequence`。没有旧 Cold 摘要时，仅使用待迁移节点生成第一份摘要。

## 7. 摘要文档

Cold 和 Warm 使用相同的版本化 JSON schema，首版至少包含：

- `confirmedFacts`：已经确认且后续仍有意义的事实。
- `workingHypotheses`：仍在验证、不能当作事实的假设。
- `decisions`：已经选择的方案及简短理由。
- `rejectedApproaches`：已经排除的路线及原因。
- `toolFindings`：工具名称、关键输入语义、结果、失败码和失败信息。
- `nextSteps`：尚未完成且仍然有效的执行步骤。
- `openQuestions`：未解决的问题、歧义和阻塞项。

摘要模型可以读取来源范围内的全部 message 和 `thinkingProcess`，但输出不得包含逐字推理过程。
摘要必须：

- 不臆造来源中不存在的信息；
- 明确区分事实、假设、决策和排除项；
- 保留标识符、时间范围、过滤条件、指标口径、单位和工具失败信息；
- 删除已经被后续内容明确推翻的旧结论，同时保留最终有效结论；
- 只输出符合固定 schema 的 JSON；
- 满足单节点字段数量、字段长度和 token 上限。

应用层验证 schema、覆盖区间、字段类型、体积和版本。结构非法或超限均视为摘要生成失败。

## 8. 持久化模型

### 8.1 `conversation_context_checkpoint`

每个会话只有一条当前有效 Cold 检查点：

| 字段 | 含义 |
| --- | --- |
| `conversation_context_checkpoint_id` | 业务唯一标识 |
| `tenant_id` | 租户隔离键 |
| `conversation_session_id` | 所属会话 |
| `cold_summary_document` | 当前结构化 Cold 摘要 |
| `summarized_up_to_conversation_message_sequence` | Cold 已覆盖的最大消息序号 |
| `summarized_completed_turn_count` | 已覆盖的完整问答数量 |
| `estimated_token_count` | 摘要保守 token 估算 |
| `summary_schema_version` | 摘要文档版本 |
| `summary_prompt_version` | 摘要 prompt 版本 |
| `model_provider` / `model_name` | 生成摘要的模型标识 |
| 审计、逻辑删除和 `version` | 与现有租户审计实体一致 |

`(tenant_id, conversation_session_id)` 在有效记录中唯一。指针非负且只能向前移动。摘要文档、
指针、token 数和生成版本通过同一条 CAS 更新原子提交。

### 8.2 `conversation_context_summary_node`

Warm 节点使用不可变记录：

| 字段 | 含义 |
| --- | --- |
| `conversation_context_summary_node_id` | 节点业务唯一标识 |
| `tenant_id` / `conversation_session_id` | 租户和会话边界 |
| `level` | 二进制层级 |
| `covered_completed_turn_count` | 覆盖完整问答数，必须等于 `2^level` |
| `start_conversation_message_sequence` | 覆盖起始消息序号 |
| `end_conversation_message_sequence` | 覆盖结束消息序号 |
| `left_child_node_id` / `right_child_node_id` | 非叶节点来源 |
| `summary_document` | 结构化摘要 |
| `estimated_token_count` | 节点保守 token 估算 |
| `summary_schema_version` / `summary_prompt_version` | 摘要版本 |
| `model_provider` / `model_name` | 生成模型 |
| `created_at` | 生成时间 |

约束包括：

- 起始序号不大于结束序号。
- 叶节点覆盖一个完整问答且没有子节点。
- 父节点具有两个子节点，子节点同层、相邻且版本兼容。
- 父节点覆盖区间恰好等于左右子节点连续并集。
- 相同租户、会话、覆盖区间、层级、schema、prompt 和模型版本只能有一个节点。
- 节点创建后不修改；模型或 schema 变化时创建新版本。

不持久化某次模型调用选择的 Warm frontier；`ConversationContextAssembler` 每次根据预算和覆盖
关系重新确定。

## 9. 组件边界

### 9.1 `agent-domain`

- Cold 检查点领域模型。
- Warm 摘要节点领域模型。
- 版本化摘要文档及覆盖范围值对象。
- 上下文预算和压缩计划值对象。

### 9.2 `agent-persistence`

- 新增 Flyway 迁移、Entity、Mapper 和基于 `ServiceImpl` 的持久化服务。
- 查询必须受 tenant context 隔离。
- 其他模块不得直接引用 Mapper 或 MyBatis API。
- 提供 checkpoint CAS、节点幂等插入以及按覆盖范围读取节点的能力。

### 9.3 `agent-web`

- `ConversationTurnGrouper`：识别完整问答、retry 归属和当前未完成问答。
- `ConversationTokenEstimator`：估算所有模型输入组成。
- `ContextBudgetPolicy`：统一计算 75% 触发线、70% 目标线和硬输入上限。
- `ConversationSummaryTreeService`：生成叶节点、合并父节点并验证覆盖关系。
- `RollingContextCheckpointService`：把移出近期窗口的完整节点融合进 Cold。
- `ConversationContextAssembler`：选择 Cold、Warm frontier、Hot 原文和当前轮。
- `ContextCompactionCoordinator`：协调快照、lease、模型调用、CAS 和事件。
- `SummaryModelGateway`：执行不携带 tools 的结构化摘要调用。
- `OllamaAgentModelGateway`：支持普通历史中的 assistant thinking 字段。

组件边界保持单一职责。预算策略不访问数据库，节点服务不决定 execution 所有权，模型网关不
决定压缩范围，上下文组装器不修改摘要。

## 10. 上下文组装与压缩算法

每次正常模型调用前执行：

1. 使用 `inputUpToConversationMessageSequence` 固定本次可读消息上界。
2. 读取 Cold 检查点、指针之后的消息和适用版本的 Warm 节点。
3. 重建跨 execution 的 message、thinking、tool call 和 observation。
4. 按完整问答分组，识别当前未完成问答和最近 10 个 Hot 问答。
5. 组装当前可用的最详细、无重叠上下文并估算完整输入 token。
6. 预计占用不超过 75% 时直接调用正常模型。
7. 超过 75% 时创建固定边界的 `CompactionPlan` 并写入开始事件。
8. 把已经完整移出最近 100 轮的连续节点规划为 Cold 迁移范围。
9. 自底向上生成达到目标粒度所缺少的 Warm 节点。
10. 生成并原子提交新的 Cold 摘要和指针。
11. 从最老 Warm 历史开始使用父节点替换子节点，较新历史保持较细粒度。
12. 重新估算，正常情况下直到预计占用不高于 70%。
13. 写入完成事件，再调用正常模型。

如果 Cold 和 Warm 已经使用最粗可用表示，而固定内容、Cold 和 Hot 使输入仍高于 70% 但没有
超过硬输入上限，则保留全部 Hot 原文并允许本次压缩以 `targetReached=false` 完成。该状态表示
保护区形成了不可继续压缩的最低水位，不能为了机械达到 70% 而破坏最近 10 轮保护。

只有输入仍高于硬输入上限时，才从 Hot 中最老的完整问答开始降级压缩。降级产生的摘要仍是
不可变二进制摘要节点，不推进 Cold 指针；上下文组装器在该受保护区间使用摘要节点替代对应
原文，并在完成事件中记录 `degradedHotTurnCount`。后续调用只有在原文可以安全进入预算时才
恢复使用原文。当前未完成问答永不压缩；如果固定内容和当前轮本身超过硬上限，execution 以
`CONTEXT_WINDOW_EXCEEDED` 失败。

摘要请求自身也必须满足硬输入上限。规划器只能按完整节点或完整问答拆分批次，禁止截断一个
问答或拆开 tool call/observation。多个节点可以在一次有界摘要请求中批量生成，但每个返回
节点必须独立验证并独立幂等提交。

## 11. 摘要树选择

Warm 可用预算为：

```text
warmTreeBudget =
    targetBudget
  - systemPromptTokens
  - toolDefinitionTokens
  - protocolOverheadTokens
  - coldSummaryTokens
  - hotRawTurnTokens
  - currentIncompleteTurnTokens
```

选择器从覆盖整个 Warm 区间的节点集合开始：

1. 优先确保全范围覆盖，无重叠、无缺口。
2. 优先压粗最老的节点。
3. 在剩余预算内，按时间从新到旧展开父节点以获得更多细节。
4. 每次展开必须用父节点的两个直接子节点完整替换父节点。
5. 最终 frontier 的 token 总量不得超过 `warmTreeBudget`。

如果现有节点不足以构成合法 frontier，则返回缺失覆盖范围，由压缩协调器生成节点；选择器
本身不调用 LLM。

## 12. 事务、并发与恢复

每个压缩周期固定：

```text
CompactionPlan
- executionId / owner / attempt
- inputUpToConversationMessageSequence
- oldCheckpointVersion
- oldColdPointer
- completedTurnBoundary
- coldPromotionRange
- warmTreeBuildRanges
- targetTokenBudget
```

### 12.1 开始

短事务校验 execution owner、attempt、lease、取消状态和截止时间，固定消息边界和旧 checkpoint
版本，并写入 `context_compaction_started`。事件只包含版本、覆盖范围、节点数量和 token 估算。

### 12.2 生成

摘要模型调用在事务外进行：

- 不携带业务 tools。
- 不发布 reasoning 或 answer delta。
- 受 execution heartbeat、取消和硬截止时间约束。
- 每个摘要生成单元最多尝试两次。
- 固定左后右的来源顺序以及 schema、prompt 和模型版本。

有效 Warm 节点通过短事务幂等插入。并发 worker 生成同一节点时，唯一约束的胜者提交，其他
worker 读取并校验已提交节点后继续。

### 12.3 提交 Cold

生成新 Cold 摘要后，短事务重新校验 owner、attempt 和消息边界，并使用旧 checkpoint version
进行 CAS。只有摘要校验成功且指针严格前进时才能提交。旧 owner、过期 attempt 或版本不匹配
的结果直接丢弃。

### 12.4 完成

提交后重新组装上下文。覆盖完整且预计占用不高于 70% 时写入
`context_compaction_completed`。如果由于最近 10 轮保护形成不可压缩最低水位，则允许在不
超过硬输入上限时以 `targetReached=false` 完成。随后才可调用正常模型。

## 13. 错误处理

- 单个摘要生成两次失败、结构持续非法或覆盖范围非法：
  `CONTEXT_COMPACTION_FAILED`。
- 固定内容和当前未完成问答本身超过硬输入上限：`CONTEXT_WINDOW_EXCEEDED`。
- Hot 降级压缩后仍无法进入硬输入上限：`CONTEXT_WINDOW_EXCEEDED`。
- 压缩失败不移动 Cold 指针，也不替换当前有效 Cold 摘要。
- 压缩周期中已经成功生成的不可变 Warm 节点可以保留，execution retry 可以复用。
- 进程崩溃时没有提交的模型结果丢失；新 owner 使用相同持久化边界重新规划。
- 用户取消时停止生成，不提交临时 Cold 结果，并沿用现有 cancelled 收敛流程。
- 不允许通过丢弃任意消息、拆分完整问答或破坏工具协议配对来降级成功。

## 14. 事件与安全

实现契约已经允许的事件：

- `context_compaction_started`
- `context_compaction_completed`
- `context_compaction_failed`

事件、日志和指标只记录：

- execution、attempt 和安全关联 ID；
- 旧、新指针和覆盖范围；
- 节点层级、数量、版本和模型标识；
- 预计 token、实际 `prompt_eval_count`、耗时和稳定错误码。

不得记录 message、thinking、tool input/output、摘要 prompt 或摘要正文。Redis 继续只承担低延迟
通知，PostgreSQL 是摘要节点、检查点和 execution 事件的事实源。

## 15. 测试策略

### 15.1 预算与分组单元测试

- 75% 触发线、70% 目标线和硬输入上限计算。
- system、tools、协议、Cold、Warm、Hot 和当前轮全部参与估算。
- 中英文、JSON、tool call、thinking 和混合内容的保守估算。
- 完整问答、失败 execution retry、未完成问答和工具原子组分组。
- 非法比例、窗口和保护数量导致启动失败。

### 15.2 摘要树单元测试

- `level=n` 恰好覆盖 `2^n` 个完整问答。
- 父节点只能合并相邻、同层和版本兼容的子节点。
- frontier 覆盖完整、无重叠、无缺口且不同时包含父子节点。
- 预算减少时优先压粗旧节点；预算增加时优先展开新节点。
- 相同输入和节点集合得到确定的 frontier。
- schema、范围和 token 上限验证拒绝非法模型输出。

### 15.3 持久化测试

- Flyway 表、复合租户外键、唯一约束和非负约束。
- checkpoint 指针只能向前移动，旧 version CAS 失败。
- Warm 节点不可修改且并发幂等插入只保留一个版本。
- 不同租户和不同会话不能读取或引用彼此节点。

### 15.4 上下文组装测试

- 不超过 75% 时不调用摘要模型。
- 超过 75% 时压缩后不超过 70%。
- system prompt、工具定义和协议内容逐字保持不变。
- 最近 10 轮正常使用原始 message、thinking 和完整工具协议。
- Cold 指针之前的原始消息不再重复发送。
- 第 11～100 轮由合法 Warm frontier 覆盖。
- 保护区最低水位处于 70% 与硬上限之间时不压缩 Hot，也不对同一快照无限重试。
- 硬上限时从 Hot 最老问答开始生成紧急摘要节点，当前轮不被压缩且 Cold 指针不移动。

### 15.5 恢复与集成测试

- 首次压缩、滚动 Cold 更新和多次树节点合并。
- 第二次压缩使用旧 Cold 摘要、旧指针之后的增量和新 Warm 节点。
- 摘要开始、完成和失败事件可经 PostgreSQL 重放与 SSE reconciliation 收敛。
- Redis 不可用不影响摘要提交。
- 并发 worker、lease takeover、旧 owner CAS、取消、硬截止时间和 execution retry。
- 第一次摘要失败第二次成功，以及两次失败保持旧 checkpoint。
- 正常模型可以延续摘要中的事实、工作假设、方案取舍、工具发现和下一步。

## 16. 验收条件

满足以下条件后视为实现完成：

1. 完整模型输入预计占上下文窗口超过 75% 时进入压缩流程。
2. 正常压缩完成后的预计输入占用不高于 70%；保护区自身形成更高最低水位时明确记录未达到
   目标，但输入仍不得超过硬上限。
3. system prompt、工具定义、权限和模型协议框架不被压缩或改写。
4. 最近 10 个完整问答正常保留原始 message 和 thinking；只有硬上限场景允许从最老轮次降级。
5. 最近 100 轮中的历史由合法二进制摘要 frontier 与 Hot 原文完整覆盖。
6. 100 轮之前的历史进入 Cold 滚动摘要，指针只前进且不会重复摘要或遗漏。
7. 摘要读取全部历史过程，但只保存结构化思路状态，不保存逐字推理。
8. 原始 message 和 `thinkingProcess` 不被修改或删除。
9. 崩溃、失败、重试、取消和多实例接管均保持确定、可恢复和可审计。
10. 受影响模块测试及后端完整构建通过。
