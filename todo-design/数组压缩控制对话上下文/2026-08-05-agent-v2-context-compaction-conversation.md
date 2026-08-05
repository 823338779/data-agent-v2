# Data Agent V2 上下文压缩设计会话导出

## 导出说明

- 导出日期：2026-08-05
- 会话时区：Asia/Shanghai
- 讨论范围：Data Agent V2 会话上下文压缩、三层记忆、二进制摘要树和滚动摘要检查点
- 导出内容：用户诉求、逐轮设计决策、关键解释和最终产物
- 不包含：平台 system/developer 指令、工具调用原始载荷和内部推理

相关产物：

- 最终设计：
  [`data-agent-v2/docs/superpowers/specs/2026-08-05-hierarchical-context-compaction-design.md`](../../data-agent-v2/docs/superpowers/specs/2026-08-05-hierarchical-context-compaction-design.md)
- Data Agent V2 提交：`01d24e9 docs: 设计分层上下文压缩方案`

## 最终决策速览

1. 完整模型输入预计占上下文窗口超过 75% 时触发压缩。
2. 正常压缩目标水位为 70%。
3. system prompt、工具定义和协议开销参与 token 估算，但永不被压缩或替换。
4. 最近 10 个完整问答正常保留原始 `message`、`thinkingProcess` 和工具协议。
5. 最近 100 个完整问答中，最近 10 轮属于 Hot，其余近期历史属于 Warm。
6. Warm 使用 Fenwick 风格的二进制摘要树；父节点覆盖两个相邻同层子节点。
7. 100 轮之前的历史进入 Cold 单一滚动摘要，并保存单调前进的消息序号指针。
8. 新 Cold 摘要由“旧 Cold 摘要 + 指针之后待迁移的完整节点”融合生成。
9. 摘要模型可以读取历史消息和全部 thinking，但只保存结构化思路状态，不保存逐字推理。
10. 结构化摘要包含事实、假设、决策、排除方案、工具发现、下一步和开放问题。
11. 预算宽松时使用较细的 Warm 子节点；预算紧张时用父节点替换一组子节点。
12. 正常压缩不触碰 Hot；只有预计输入超过硬上限时，才从 Hot 最老问答开始降级压缩。
13. 当前未完成问答永不压缩。
14. 原始消息和 thinking 永不因压缩被修改或删除。
15. Cold 摘要和指针使用 CAS 原子提交；Warm 节点不可变且支持幂等并发生成。

## 关键公式

设模型上下文窗口为 `W`：

```text
softTrigger    = floor(W × 0.75)
targetBudget   = floor(W × 0.70)
hardInputLimit = W - maxOutputTokens - safetyReserveTokens
```

完整输入估算：

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

Warm 摘要树可用预算：

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

以 `W=65536`、`maxOutputTokens=4096`、`safetyReserveTokens=4096` 为例：

```text
softTrigger    = 49152
targetBudget   = 45875
hardInputLimit = 57344
```

## 三层记忆结构

完成问答总数为 `N` 时，压缩周期的目标逻辑边界为：

```text
Cold：第 1 ～ N-100 轮
Warm：第 N-99 ～ N-10 轮
Hot： 第 N-9 ～ N 轮
```

二进制摘要节点：

```text
level 0：1 个完整问答
level 1：2 个完整问答
level 2：4 个完整问答
level 3：8 个完整问答
level n：2^n 个完整问答
```

例如 Warm 区间可以由以下无重叠节点覆盖：

```text
[58..89]   32 轮
[90..121]  32 轮
[122..137] 16 轮
[138..145] 8 轮
[146..147] 2 轮
```

父节点和其子节点不能同时进入一次模型输入。预算宽松时展开父节点，预算紧张时使用父节点。

## 结构化摘要内容

```text
confirmedFacts
workingHypotheses
decisions
rejectedApproaches
toolFindings
nextSteps
openQuestions
```

摘要可以从 message、thinking、tool call 和 tool observation 中提炼上述状态，但不保留逐字
推理文本。

## 逐轮讨论记录

### 1. 当前能力核实

**用户：**

> 现在的agent具备上下文的压缩能力吗

**结论：**

当前 Data Agent V2 尚不具备真正的会话上下文压缩能力。运行链路会按消息上界加载历史；
`context_compaction_started/completed/failed` 已存在于 OpenAPI 契约，旧设计文档也描述了滚动
摘要，但后端没有 token 估算、摘要生成或压缩协调实现。现有 `truncated` 主要用于工具载荷和
思考内容的持久化截断，不等同于会话摘要压缩。

### 2. 初始诉求

**用户：**

> 帮我设计一下：我想用一种压缩方式，先根据上下文配置预估当前的上下文，如果超过75%，
> 那么进入上下文压缩流程。
>
> 压缩的时候要确保最近的10次问答不能被压缩，因为要保留最新的上下文。10次之前的问答
> 内容要被压缩。

设计从现有滚动摘要方案出发，将旧设计中的默认最近 5 轮调整为最近 10 轮，并新增 75% 触发
水位。

### 3. 最近 10 轮是否为硬保护

提出的边界问题：如果“最近 10 个完整问答 + 当前未完成问答”本身超过模型硬上限，是明确
失败，还是允许从保护区最老问答开始降级压缩？

**用户选择：**

> B

即：正常情况下保护最近 10 轮；只有硬上限场景允许从其中最老问答开始压缩。

### 4. 滚动摘要和指针

**用户：**

> 如果之前已经有压缩得到的上下文，那么要记一个指针，指针之后的对话内容到10次之前的
> 对话内容，融合在一起让LLM进行一次上下文压缩，得到新的上下文和对应的新的指针。

确认采用滚动摘要检查点：

```text
旧摘要（若有）
+ 指针之后、本次保护边界之前的完整问答
→ 新摘要 + 新指针
```

新指针指向本次摘要实际覆盖的最后一条 `conversation_message_sequence`。摘要和指针必须
原子提交，原始消息不删除。

### 5. 75% 的计算口径

可选口径包括：输入占模型窗口比例、输入加输出预留占比、输入预算占比。

**用户选择：**

> A

即：

```text
预估完整输入 token / model context window > 75%
```

完整输入包括固定 system prompt、工具定义、协议开销和会话内容。固定内容参与估算，但不被
压缩。

### 6. 压缩范围必须与系统框架隔离

**用户：**

> 上下文压缩主要是针对之前发生的一些对话，对于系统提示词、工具定义等不应该被压缩，
> 因为它们每次都会固定作为LLM上下文传输给LLM。需要被压缩的应该是message以及
> thinkingProcess中的内容，相当于只是压缩对话的过程，需要保证系统框架持久稳定。

确认固定框架规则：

- system prompt、工具定义和协议开销始终从正式配置构造；
- 它们参与 token 估算，但不进入摘要来源；
- 摘要不得修改系统规则、工具能力、权限、租户或执行策略；
- 压缩候选只来自会话历史。

### 7. 是否保留普通 thinking

讨论了三种方式：只保留工具过程、保留全部思考文本、只提取思考结论。

**用户：**

> 如果保留B，是不是能够传递之前的思考过程 来延续之前的解决思路？

结论：完整原始 thinking 能延续思路，但会带来 token 快速增长、旧错误假设固化和摘要漂移。
因此采用折中方案：摘要模型可以读取全部 thinking，但摘要产物只保存结构化思路状态。

**用户确认：**

> 是

### 8. 第一版持久化方案选择

比较了：

1. 单一滚动摘要检查点；
2. 不可变摘要版本链；
3. 分层摘要树。

初始推荐单一滚动摘要检查点，但用户提出将滚动摘要和摘要树结合。

### 9. 三层记忆提议

**用户：**

> 我想把1、3结合来做，比如最近的100次问答使用摘要树，100次之前的问答内容使用滚动摘要
> 检查点来做。你觉得行吗

确认三层结构：

```text
Hot：最近 10 次问答，保留原文
Warm：最近 100 次中的中间历史，使用摘要树
Cold：100 次之前，使用滚动摘要和指针
```

### 10. 父节点替代子节点

**用户：**

> 预算紧张时用父节点替代一组叶节点 是什么意思？

解释示例：

```text
父节点 P：第 11～60 次问答的整体摘要
├── L1：第 11～20 次问答摘要
├── L2：第 21～30 次问答摘要
├── L3：第 31～40 次问答摘要
├── L4：第 41～50 次问答摘要
└── L5：第 51～60 次问答摘要
```

预算充足时发送 `L1 + L2 + L3 + L4 + L5`，预算紧张时发送 `P`。两者覆盖相同区间，不能
同时发送。越接近当前对话，越优先保留细粒度摘要。

### 11. 预算如何决定摘要粒度

**用户：**

> 预算怎么计算呢？

确认使用软触发、目标水位和硬上限三层预算。留给 Warm 树的预算等于目标水位扣除固定框架、
Cold 摘要、Hot 原文和当前轮之后的剩余 token。

提出压缩目标水位 65%、70% 或仅回到 75% 以下。

**用户确认：**

> 75% 触发、70% 目标水位

### 12. 根据空闲 token 选择摘要

**用户：**

> 你的意思是对于上下文窗口的空闲token来抉择应该如何选择之前的摘要吗

确认：更准确地说，是根据扣除固定内容、Cold 和 Hot 后留给 Warm 的 token 预算，选择摘要
粒度，而不是按当前问题随意挑选历史。所有 Warm 历史都必须由一组互不重叠、无缺口的节点
覆盖。

生成节点和选择节点是两个动作：生成缺失节点需要 LLM；从已有树中选择 frontier 不需要
调用 LLM。

### 13. Fenwick 风格的二进制摘要结构

**用户：**

> 能否把它搞成一个类似于树状数组的形式，因为树状数组天然会聚合一部分前置信息

结论：可以使用 Fenwick 风格的二进制聚合思想，但不能照搬数值树状数组的差分能力。LLM
摘要不可相减，也不严格满足结合律。

确定节点范围按 `2^level` 聚合，相邻同层节点按固定左右顺序生成父节点。每个节点保存覆盖
消息序号、子节点、摘要 schema、prompt 版本、模型版本和 token 估算。

**用户确认：**

> 符合

### 14. 触发和压缩算法确认

设计确认：

1. 输入不超过 75% 时直接调用正常模型。
2. 超过 75% 时冻结消息边界并启动压缩。
3. 将完整移出最近 100 轮的节点融合进 Cold。
4. 为 Warm 生成缺失的二进制摘要节点。
5. 从最老历史开始降低粒度，较新历史尽量保持详细。
6. 正常目标为 70%。
7. Cold/Warm 已最粗且仍超过硬上限时，从 Hot 最老问答开始降级。
8. 当前未完成轮永不压缩。

**用户确认：**

> 确认

### 15. 组件与数据模型确认

确认新增：

- `conversation_context_checkpoint`：每会话一条 Cold 摘要和指针，使用乐观锁 CAS。
- `conversation_context_summary_node`：不可变 Warm 二进制摘要节点。
- `ConversationTurnGrouper`。
- `ConversationTokenEstimator`。
- `ContextBudgetPolicy`。
- `ConversationSummaryTreeService`。
- `RollingContextCheckpointService`。
- `ConversationContextAssembler`。
- `ContextCompactionCoordinator`。
- `SummaryModelGateway`。

Hot 中的历史 thinking 使用模型协议支持的 assistant thinking/history 字段传递，不拼入 system
prompt。

**用户确认：**

> 确认

### 16. 并发、恢复和失败规则确认

压缩使用固定的 `CompactionPlan`，模型调用期间不持有数据库事务。Warm 节点幂等插入，Cold
使用 owner、attempt、消息边界和旧 checkpoint version 做 CAS。

失败规则包括：

- 每个摘要生成单元最多尝试两次；
- 结构非法或范围不一致视为失败；
- 失败不移动 Cold 指针；
- 已生成的不可变 Warm 节点可以复用；
- lease 被接管后旧 worker 结果丢弃；
- 用户取消时不提交临时 Cold；
- 固定内容与当前轮自身超过硬上限时返回 `CONTEXT_WINDOW_EXCEEDED`；
- 摘要重试耗尽返回 `CONTEXT_COMPACTION_FAILED`。

**用户确认：**

> 确认

### 17. 书面设计产物

完整设计已写入：

```text
data-agent-v2/docs/superpowers/specs/
2026-08-05-hierarchical-context-compaction-design.md
```

并在 `data-agent-v2` 子仓库提交：

```text
01d24e9 docs: 设计分层上下文压缩方案
```

书面设计额外明确了一个必要边界：如果固定内容、Cold 和最近 10 轮形成的最低水位高于 70%
但尚未超过硬上限，则保持 Hot 原文，并以 `targetReached=false` 完成；只有超过硬上限才压缩
Hot 最老轮次。

## 后续状态

截至本次导出：

- 设计讨论已完成。
- 设计文档已提交。
- 尚未生成 implementation plan。
- 尚未实现代码。
- 下一步应先审阅书面设计；确认后使用 Superpowers `writing-plans` 生成详细实施计划。
