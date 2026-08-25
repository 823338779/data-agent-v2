# Data Agent V2 会话全量历史与 Hierarchical 最近轮次保护设计

## 1. 状态与范围

- 设计日期：2026-08-25。
- 设计状态：已完成方案确认，等待书面规格复核。
- 适用部署：单实例后端。
- 修改范围：会话消息公共查询能力、Hierarchical 历史重建、滚动 Cold 规划、上下文组装与相关测试。
- 明确不修改：LSM 的读取边界、manifest、segment、规划、压缩、组装和持久化行为。
- 明确不修改：Raw 的有界历史读取和上下文组装行为。
- 明确不修改：REST/SSE 对外契约、execution 状态机、消息提交幂等、同会话活动 execution 唯一约束。

本文覆盖从用户向既有会话提交新消息、创建并启动 execution，到 Hierarchical 整合会话历史并形成一次 LLM 输入的完整过程。本文取代既有设计中“Hierarchical 依赖消息序号上界读取”和“通过 assistant 是否有最终正文区分完整历史与当前轮次”的规定；其他不冲突内容继续有效。

## 2. 背景与根因

当前提交事务会为一次新请求创建：

1. 一条 user 消息；
2. 一条空正文 assistant 占位消息；
3. 一条 `running` execution；
4. 一个交给本机虚拟线程的 `LocalAgentExecution`。

正常完成时，assistant 占位消息被最终回答填充。execution 被取消、进程重启或执行失败时，assistant 可能保持空正文。当前 Hierarchical 按“assistant 正文非空”判断轮次完成，并从所有未完成轮次中使用 `findFirst()` 选择当前轮次。

这会使最早的失败或取消轮次永久成为所谓当前轮次。后续真正的新问题虽然已经成功持久化，却既不属于 `completedTurns`，也不是 `findFirst()` 选中的轮次，最终不进入 LLM 输入。故障 session `conversation_session_f767f85496534b04968edb23a534a368` 已经证明这一行为：历史中的空 assistant 使后续“你能调用哪些工具？”和“你好”被旧问题替代。

根因不是消息未保存，也不是 Codex thread 缓存，而是 Hierarchical 把内容完整性误当成轮次身份，并假设会话中最多存在一个空 assistant 轮次。

## 3. 已确认的设计决策

### 3.1 会话执行期间禁止追加 user 消息

同一会话最多存在一个 `running` execution。提交服务在发现活动 execution 时继续返回冲突，不允许在该 execution 运行期间追加新的 user 消息。

因此，一次 execution 启动后：

- 会话中的 user 轮次集合保持不变；
- 正在执行的请求自然是最后一个 user 轮次；
- ReAct 只会更新最后一个轮次内的 assistant thinking、tool call、tool result 和最终回答；
- 不需要通过 triggering message ID 定位当前问题。

### 3.2 Hierarchical 读取会话全量消息

Hierarchical 每次准备上下文时，按 `conversationSessionId` 读取当前会话全部 user/assistant 消息，并按 `conversationMessageSequence` 升序排列。查询不再使用 `inputUpToMessageSequence` 作为上界。

消息序号继续保留，用于：

- 会话内稳定排序；
- 轮次起止范围；
- Cold 摘要覆盖范围；
- checkpoint 水位、指标和审计。

消息序号不再负责截断 Hierarchical 的事实源。

### 3.3 只按 user 消息划分轮次

每条真实 user 消息开启一个新轮次。在下一条 user 消息出现前，后续 assistant 中心消息及其展开出的 thinking、tool call 和 tool observation 都归属该轮次。

轮次不再包含 `completed`、`terminal` 或 `current` 状态。execution 的 `done`、`failed`、`cancelled`、`running` 状态不参与 Hierarchical 的轮次分组和摘要资格判断。

### 3.4 逆序保护最近 N 轮

Hierarchical 始终将最后 `recentRawTurns` 个轮次作为原文保护后缀，默认值为 10。更早的连续前缀才允许滚入 Cold 摘要。

正在运行的请求必然位于最后一个轮次，因此自然包含在受保护后缀内，不需要单独识别当前轮次。

`recentRawTurns` 以 `HierarchicalContextPolicySnapshot` 为唯一事实源。planner、assembler 和预算计算不得各自硬编码 10。

### 3.5 LSM 保持不变

为了保证 LSM 零行为变化，共享请求中的 `inputUpToMessageSequence` 暂时保留，提交链路继续计算并传递该值。LSM 仍可按其现有逻辑使用该上界。

Hierarchical 明确忽略该字段的查询限制，只使用全量会话查询结果计算本次观察水位。未来若要移除共享字段，必须作为独立 LSM 迁移设计处理，不在本次范围内完成。

## 4. 方案选择

### 4.1 采用方案：全量读取加最近轮次保护

采用统一有序轮次列表：

```java
List<ConversationTurn> turns;
```

规划器根据列表长度和 `recentRawTurns` 计算：

```text
可压缩前缀 = [第 1 轮, 第 total-recentRawTurns 轮]
原文保护后缀 = [第 total-recentRawTurns+1 轮, 第 total 轮]
```

该方案只依赖已经成立的单实例提交约束和 user 消息顺序，不引入新的 execution 状态耦合。

### 4.2 不采用：按 execution 状态分类轮次

该方案可以把 `done/failed/cancelled` 视为历史、`running` 视为当前，但 Hierarchical 实际只需要确定可压缩前缀。既然运行期间不能追加 user 消息，最近轮次保护已经覆盖当前请求，引入状态分类不会增加正确性，反而扩大查询和领域模型复杂度。

### 4.3 不采用：为失败占位消息写入虚假正文

通过给失败或取消的空 assistant 写入占位回答，可以绕过旧的 `completed` 判断，但会污染用户可见会话、摘要事实和 LLM 输入，并继续把轮次语义错误地绑定到正文是否为空。因此不采用。

## 5. 目标架构

```text
消息提交事务
  -> 锁定 session
  -> 校验幂等与活动 execution
  -> 写入 user、assistant 占位、running execution、started event
  -> 提交后启动本机虚拟线程

ReAct 模型调用
  -> 校验 execution 仍为 running 且未超时
  -> 创建模型调用边界
  -> 构建 ConversationContextRequest
  -> 选择 hierarchical 策略
  -> 按 session 全量读取消息
  -> 按 user 边界重建全部轮次
  -> 读取当前 Cold checkpoint
  -> 组装并估算完整输入
  -> 必要时把最近 N 轮之前的前缀滚入 Cold
  -> 重新组装最终上下文
  -> 映射为 ModelInputMessage
  -> Codex/Ollama 模型网关生成实际提示词并调用 LLM
```

## 6. 消息提交与执行启动

### 6.1 提交事务

`AgentExecutionSubmissionTransactionService` 继续执行以下原子操作：

1. 通过会话锁读取 `ConversationSession`；
2. 根据 request type、幂等键和 payload hash 处理重放；
3. 锁定并拒绝同会话已有活动 execution；
4. 分配连续的 user 与 assistant 消息序号；
5. 保存 user 消息；
6. 创建 `running` execution，并把 user 消息关联到 execution；
7. 保存同 execution 的 assistant 占位消息；
8. 保存 started event 和初始 stream checkpoint；
9. 在事务提交后发布 `LocalExecutionStarted`。

本次不删除 `agent_execution.triggering_conversation_message_id`。它继续承担数据库外键、审计和故障定位职责，但 Hierarchical 上下文不读取它。

### 6.2 不可追加约束

活动 execution 冲突检查和数据库部分唯一索引共同保证同一会话最多一个 `running` execution。任何绕过正常提交服务的写入都不属于受支持路径。

该不变量是“最后 N 轮自然保护当前请求”的成立前提。测试必须证明冲突提交不会产生新的 user 消息或推进会话消息水位。

## 7. Hierarchical 全量历史加载

### 7.1 持久化查询

`ConversationMessageService` 新增语义明确的全量查询：

```java
List<ConversationMessage> findByConversationSessionId(String conversationSessionId)
```

查询条件为：

```text
tenant_id = TenantContext 当前租户
conversation_session_id = 指定会话
role in (user, assistant)
is_deleted = false
order by conversation_message_sequence asc
```

现有 `findByConversationSessionIdUpTo(...)` 保留，继续服务 Raw 或 LSM 等既有调用方。本次不得通过修改其内部语义来暗中影响 LSM。

### 7.2 加载校验

`HierarchicalConversationHistoryLoader` 使用全量查询，并验证：

- 所有消息属于目标会话；
- 消息序号严格递增且为正数；
- role 只能是 user 或 assistant；
- content 不为 `null`；
- 第一条能够进入上下文的消息必须是 user；
- assistant 展开的 tool call 与 terminal observation 保持合法配对。

本次观察水位取最后一条消息的 `conversationMessageSequence`；空会话为 0。该水位随规划结果进入 checkpoint 和指标，但不反向限制消息查询。

### 7.3 Assistant 重建

assistant 中心消息继续按 `modelInvocationSequence` 重建：

1. thinking 片段按过程序号合并；
2. 同一次模型调用的 tool process 形成一条 assistant tool-calls 消息；
3. 已终态工具节点形成对应 tool observation；
4. 最终非空 assistant 正文形成普通 assistant 消息；
5. 空 assistant 占位不生成模型协议消息。

失败或取消轮次即使只有 user 消息，也必须保留为合法轮次，不得因缺少 assistant 正文而丢弃。

## 8. 统一轮次模型

`ConversationTurn` 收敛为：

```java
public record ConversationTurn(
        long ordinal,
        long startMessageSequence,
        long endMessageSequence,
        List<ContextMessage> messages) {}
```

约束如下：

- `ordinal` 从 1 连续递增；
- `startMessageSequence` 是开启该轮的 user 消息序号；
- `endMessageSequence` 是下一条 user 之前最后一条已读取持久化消息序号；
- `messages` 第一条必须是 user；
- `messages` 至少包含该 user 消息；
- 工具调用与 observation 不能跨轮次；
- 不包含 `completed` 字段。

`ConversationTurnGrouper` 的确定性算法为：

1. 遇到 user 时，提交上一轮并开启新轮；
2. 将当前 user 加入新轮；
3. 遇到 assistant 时，把其展开出的零到多条协议消息追加到最近一轮；
4. assistant 即使展开为空，也只推进该轮 `endMessageSequence`，不改变轮次资格；
5. 输入结束时无条件提交最后一轮。

## 9. Hierarchical 状态与上下文组装

### 9.1 状态模型

`HierarchicalContextState` 不再维护 `completedTurns` 和 `currentTurn`，改为维护唯一有序列表：

```java
HierarchicalContextState(
    ConversationContextRequest request,
    HierarchicalContextPolicySnapshot policy,
    List<ConversationTurn> turns,
    long loadedThroughMessageSequence,
    HierarchicalCheckpoint checkpoint,
    int estimatedTokens
)
```

### 9.2 当前视图

组装当前视图时：

1. 若 checkpoint 引用活动 Cold，先验证并渲染 Cold；
2. 记录 Cold 覆盖的 `endTurnSequence`；
3. 按 ordinal 追加所有未被 Cold 覆盖的轮次原文；
4. 不再单独追加所谓当前轮次；
5. 固定 system 消息仍位于最终输入最前方。

核心规则为：

```java
turns.stream()
        .filter(turn -> turn.ordinal() > coldCoveredThrough)
        .forEach(turn -> messages.addAll(turn.messages()));
```

因此，新问题始终以最后一个 user 轮次进入上下文。历史中任意数量的空 assistant 都不会改变顺序或选择结果。

### 9.3 原文保护后缀

保护数量由 `policy.recentRawTurns()` 提供：

```text
protectedCount = min(totalTurns, recentRawTurns)
protectedStartOrdinal = totalTurns - protectedCount + 1
compressibleEndOrdinal = protectedStartOrdinal - 1
```

当总轮次不超过保护数量时，没有可压缩前缀。

## 10. Rolling Cold 压缩规划

### 10.1 可压缩范围

planner 接收全部轮次，不过滤 `completed`：

```text
sourceEnd = 活动 Cold 已覆盖的末轮；没有 Cold 时为 0
compressibleEnd = max(0, totalTurns - recentRawTurns)
rawStart = sourceEnd + 1
rawRange = [rawStart, compressibleEnd]
targetCoverage = [1, compressibleEnd]
```

仅当 `rawStart <= compressibleEnd` 时存在新的摘要输入。最近原文保护后缀始终是：

```text
[compressibleEnd + 1, totalTurns]
```

planner 的首个序号参数从 `inputUpToMessageSequence` 改名为
`loadedThroughMessageSequence`。它来自本次全量查询观察到的最大消息序号，只写入计划、覆盖和
checkpoint 元数据，不参与筛选轮次。规划模型不再包含 `currentTurnSequence`，也不再计算完成
轮次集合。

### 10.2 摘要批次

新增进入 Cold 的轮次按完整轮次拆分为有界批次：

- 单个轮次不能跨批次拆分；
- tool call 与 observation 不能拆开；
- 每个批次不超过摘要输入硬预算；
- 单轮自身超过摘要输入硬预算时明确失败；
- 批次覆盖范围使用真实 ordinal 和消息序号。

失败或取消留下的 user-only 轮次可以作为普通摘要输入。摘要模型只总结可观察的对话事实，不补造不存在的 assistant 回答。

### 10.3 预算保护

token 预算计算必须包含：

- 固定 system 消息；
- 工具定义与协议开销；
- 已发布 Cold；
- Cold 之后尚未压缩的较早轮次；
- 最近 `recentRawTurns` 轮原文。

最近原文后缀本身和固定输入超过模型硬输入上限时，抛出稳定的上下文窗口溢出错误。本次不增加紧急压缩最近轮次或静默丢弃消息的降级路径。

## 11. Cold 生成、发布与重组装

压缩触发后：

1. planner 根据全量轮次和活动 Cold 生成内存计划；
2. generator 使用旧 Cold 与 `rawRange` 批次生成新 Cold；
3. 生成期间不持有数据库事务；
4. 生成后重新校验 execution 仍为 `running` 且未超时；
5. 候选 Cold 通过不可变仓储插入或选择；
6. checkpoint 通过 CAS 原子切换到候选 Cold；
7. checkpoint 的 evaluated 水位写入 `loadedThroughMessageSequence`；
8. 发布后重新全量加载会话，并按照新 Cold 覆盖范围重组装上下文；
9. 最终输入超过硬上限时失败，不调用正常回答模型。

同一 execution 内不会出现新 user 消息，但 ReAct 可能更新 assistant thinking 和工具结果。因此每次模型调用都重新全量加载，确保最后一轮包含已经持久化的最新过程信息。

## 12. 最终 LLM 上下文

最终中立消息顺序固定为：

```text
固定 System 消息
+ 可选的 Cold memory wrapper 与摘要
+ Cold 尚未覆盖的轮次，按原始顺序
+ 最近 N 轮原文，按原始顺序
```

其中后两项在实现上是同一个“所有 ordinal 大于 Cold 覆盖末轮的轮次”序列，不需要额外拼接当前轮。

例如：

```text
共 13 轮，recentRawTurns = 10

Cold：第 1～3 轮
原文：第 4～13 轮
当前正在执行的请求：第 13 轮，自然位于输入末尾
```

模型网关继续负责供应商适配：

- Codex 将完整中立消息序列化进 `CONVERSATION_JSON`，工具定义通过 dynamic tools 传递；
- Ollama 将中立消息映射为其 chat 消息和工具协议；
- Hierarchical 不拼接供应商专用 prompt，不改变工具定义。

## 13. 并发、取消与一致性

### 13.1 支持的不变量

- 同一会话最多一个 `running` execution；
- 有活动 execution 时不能提交新 user 消息；
- 不同会话可以并行执行；
- 当前 execution 的 assistant 过程可以在 ReAct 边界间更新；
- 每次昂贵摘要和正常模型调用前后继续校验 execution 状态与截止时间。

### 13.2 取消或失败

execution 取消或失败时，当前模型调用停止，开放过程节点按现有终止服务收敛。下一次允许提交新问题后，旧 user-only 或部分工具轮次只是按顺序存在的普通历史轮次。

上下文重建不得根据失败码删除该轮，也不得把它提升为当前问题。

### 13.3 查询一致性

由于活动 execution 期间不能追加 user 消息，轮次边界在一次 execution 生命周期内稳定。assistant 过程变化通过下一次模型调用重新加载观察，不要求长事务或数据库快照锁。

如果发现消息序号倒退、跨会话行、无起始 user 的 assistant 或非法工具协议，立即使上下文准备失败，不通过猜测或排序修复脏数据。

## 14. 既有 Hierarchical 派生数据

旧 Cold 可能基于“只摘要 completed 轮次”的规则生成，其 turn ordinal、覆盖范围和摘要正文可能遗漏空 assistant 对应的 user 轮次。新实现不得把这种 Cold 当成兼容数据继续使用。

部署迁移应只重置 Hierarchical 派生数据：

1. 删除或失效所有 hierarchical checkpoint；
2. 删除不再被引用的 hierarchical Cold；
3. 保留 conversation session、message、execution、thinking process 和 LSM 数据；
4. 会话下一次使用 Hierarchical 时从原始事实重新建立 generation 0 checkpoint，并按需生成新 Cold。

数据库迁移必须为受影响表和字段维护中文注释。清理顺序必须满足外键约束，且不得触碰 LSM 表。

## 15. 模块与代码边界

### 15.1 `agent-persistence`

- 新增全量会话消息查询服务方法；
- 保留有界查询方法及语义；
- 不增加 Hierarchical 专属 Entity、Mapper 或规划逻辑。

### 15.2 `agent-domain` 与 `agent-web`

- 共享 `ContextExecutionRef.inputUpToMessageSequence` 暂时保留，保证 LSM 不变；
- ReAct、请求工厂和策略选择器接口保持不变；
- 不引入 triggering message ID 到上下文契约；
- Raw 行为保持现状。

### 15.3 `agent-context-hierarchical`

- history loader 改用全量会话查询；
- `ConversationTurn` 删除 `completed`；
- grouper 只按 user 边界形成有序轮次；
- context state 统一保存 `turns` 和观察水位；
- assembler 删除 `completed/current` 分支；
- planner 根据总轮数和策略中的 `recentRawTurns` 计算前缀与后缀；
- coordinator 和 generator 使用新的连续覆盖范围；
- 插件迁移重置旧派生数据。

### 15.4 `agent-context-lsm`

不修改生产代码、配置、迁移和算法。现有 LSM 测试必须作为回归套件通过，以证明全量查询新增和共享类型保留没有改变 LSM 行为。

## 16. 测试策略

### 16.1 持久化测试

- 全量查询返回指定 session 的全部 user/assistant 消息；
- 查询按消息序号严格升序；
- 不返回其他租户、其他 session 或逻辑删除消息；
- 现有有界查询仍严格执行序号上限。

### 16.2 轮次分组测试

- 正常 user/assistant 形成一个轮次；
- user 加空 assistant 仍形成一个合法轮次；
- 多个历史空 assistant 不会合并或丢失 user；
- 下一条 user 无条件开启下一轮；
- thinking、tool call 和 observation 保持在所属 user 轮次；
- 最后一轮只有 user 时仍被保留；
- ordinal 和消息范围连续、确定。

### 16.3 Planner 测试

- 0～10 轮时没有可压缩前缀；
- 11 轮时只允许压缩第 1 轮；
- 13 轮时压缩第 1～3 轮并原文保护第 4～13 轮；
- 非默认 `recentRawTurns` 配置被 planner 和 assembler 一致使用；
- 旧 Cold 覆盖部分前缀时只摘要后续缺口；
- user-only 轮次可以进入完整摘要批次；
- 单轮超过摘要硬预算时稳定失败。

### 16.4 Assembler 测试

- 没有 Cold 时按原顺序输出全部轮次；
- 有 Cold 时只省略已被 Cold 覆盖的原始前缀；
- 不存在 `findFirst()` 或独立 current append；
- 多个失败或取消空 assistant 后，最新 user 始终是最后一条 user 输入；
- ReAct 第二次模型调用包含最新 terminal tool observation；
- 固定输入加最近原文超过硬上限时明确失败。

### 16.5 集成回归测试

使用与故障 session 等价的数据构造：

1. 先完成若干正常问答；
2. 创建一个 cancelled execution，assistant 保持空正文；
3. 创建一个 failed execution，assistant 保持空正文；
4. 再提交“你能调用哪些工具？”；
5. 捕获模型网关输入；
6. 断言两个旧 user 仍存在于历史；
7. 断言空 assistant 不进入模型消息；
8. 断言“你能调用哪些工具？”是最后一个 user 输入；
9. 断言模型不再把最早取消问题当作当前问题。

### 16.6 LSM 回归

- 运行现有 LSM planner、assembler、manifest、持久化和集成测试；
- 断言 LSM 仍调用有界消息查询；
- 断言 LSM schema 和生成结果无变更。

## 17. 验证顺序

实现阶段按以下顺序验证：

1. `agent-persistence` 全量与有界消息查询测试；
2. `agent-context-hierarchical` grouper、planner、assembler 单元测试；
3. Hierarchical Flyway 与派生数据重置测试；
4. `agent-server` 故障 session 回归集成测试；
5. `agent-context-lsm` 全量现有测试；
6. `agent-web` ReAct 与上下文契约测试；
7. 后端完整 Maven `verify`。

## 18. 完成标准

1. Hierarchical 不再使用消息序号上界过滤会话事实源。
2. Hierarchical 不读取 triggering message ID 来识别当前问题。
3. Hierarchical 不根据 assistant 正文或 execution 状态区分历史轮次和当前轮次。
4. 每条 user 消息稳定开启一个轮次，空 assistant 不会丢失或劫持后续问题。
5. 最近 `recentRawTurns` 轮始终保留原文，默认 10，且配置只有一个事实源。
6. 更早的连续轮次前缀按顺序进入单一 Rolling Cold。
7. 当前 execution 的用户问题和最新工具结果自然位于未压缩后缀末尾。
8. 旧 Hierarchical 派生摘要不会按新算法继续发布或读取。
9. LSM 的生产代码、查询边界、算法、持久化和行为保持不变。
10. 故障 session 回归测试、Hierarchical 测试、LSM 回归测试和后端完整构建通过。
