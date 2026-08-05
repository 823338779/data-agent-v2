# Data Agent V2 LSM-Tree 会话上下文压缩设计

## 1. 文档状态

本文定义 Data Agent V2 的会话上下文预算、分层摘要、压缩、恢复和模型输入组装规则。

本文取代 `2026-08-03-session-context-memory-design.md` 中以下内容：

- 单一滚动摘要作为唯一压缩结构；
- 默认保护最近 5 个完整问答；
- 固定摘要层级和固定合并策略。

原设计中关于会话级消息读取、原始消息作为事实源、tool call/observation 配对、租户隔离、
execution lease fencing、SSE 事件和敏感正文不进入日志的规则继续有效。

## 2. 目标

- 在完整模型输入预估超过模型上下文窗口 75% 时启动上下文压缩。
- 固定 system prompt、tool definitions 和协议框架每次保持稳定，只参与预算计算，不参与压缩。
- 最近 10 个完整问答和当前未完成问答优先保留原文。
- 使用 LSM 式不可变摘要段、分层 compaction 和唯一活动 ROOT 控制长期上下文体积。
- 每次成功压缩发布后，将 Warm 层批量收敛到 100～109 个完整问答，避免每新增一轮都调用摘要模型。
- 压缩可以读取历史 message 和全部 `thinkingProcess`，但只保留结构化、可复用的思路状态。
- `fanout` 和 `maxLevel` 由服务配置控制，并在 execution 内固化策略快照。
- LSM 能力以独立 Maven 插件模块接入，现有模块只增加稳定 SPI、策略选择和运行时装配点。
- LSM 专属 Entity、Mapper、Repository 和数据库迁移由插件模块自行持有。
- 原始 message 和 `thinkingProcess` 永不因压缩而修改或删除。
- 压缩、崩溃恢复、多实例接管、取消和 retry 具有确定且可审计的行为。

## 3. 非目标

- 不实现跨会话、跨用户或跨租户的长期记忆。
- 不引入向量数据库、embedding 检索或基于当前问题的语义节点召回。
- 不允许摘要替换或修改固定 system prompt 和 tool definitions。
- 不通过删除原始消息释放数据库空间。
- 不把逐字 thinking 写入长期摘要。
- 不支持对摘要做前缀差分、逆运算或从父摘要恢复已经损失的细节。
- 不向 REST、SSE、日志或指标暴露 message、thinking、摘要或 tool payload 正文。
- 本期不实现 LSM 以外的第二种压缩策略，但 SPI 必须允许未来策略替换。

## 4. 核心术语与问答边界

### 4.1 完整问答

一个问答从一条 user message 开始，到下一条 user message 之前结束，包含：

- user 和 assistant message；
- 此轮全部 `thinkingProcess`；
- tool call、tool input、tool output 和失败信息；
- 用户可见的最终 assistant answer。

存在最终 assistant answer 时，该问答才视为完整问答。失败 execution 后的 retry 继续引用同一
triggering user message，因此原 execution 和 retry 属于同一个问答。

最后一个没有最终 answer 的区间是当前未完成问答。它不参与 Hot 的 10 轮计数，且永不压缩。

### 4.2 Fixed、Hot、Warm 与 Cold

每个会话的模型上下文分为四类：

```text
Fixed
├── 固定 system prompt
├── tool definitions
└── 模型协议开销

Hot
├── 当前未完成问答
└── 最近 10 个完整问答原文

Warm
└── Hot 之前的 100～109 个完整问答，以 LSM 摘要节点表达

Cold
├── 唯一活动 ROOT 所覆盖的长期历史前缀
└── ROOT 之后、Warm 之前尚未并入 ROOT 的 Cold Tail
```

Warm 与 Hot 是两个连续且互不重叠的区间，因此正常情况下模型保留的近期高精度范围为
110～119 个完整问答，其中最后 10 个使用原文。

LSM 结构采用按需物化：首次超过 75% 之前，模型可以直接读取全部原始历史，不为满足层级形态
提前调用摘要模型；两次压缩之间新增的问答也可以暂时累积为原始历史。只有进入 compaction 并
成功发布后，manifest 才必须将 Warm 收敛到 100～109 轮并满足以下层级不变量。

### 4.3 不变量

- 所有摘要节点只覆盖连续、完整问答。
- ROOT 必须从会话首个问答开始覆盖连续前缀。
- 成功发布的压缩 manifest 中，ROOT、Cold Tail、Warm、Hot 和当前轮按时间连续，无重叠、无缺口。
- 同一次模型输入不能同时包含父摘要及其任一后代。
- tool call 与其 observations 是不可拆分的协议原子组。
- system prompt 和 tool definitions 不进入任何摘要输入或摘要正文。
- 原始 message 和 `thinkingProcess` 始终是最终事实源。

## 5. 插件化模块边界

### 5.1 模块与依赖方向

新增唯一的 LSM 实现模块 `agent-context-lsm`：

```text
agent-domain
└── 中立的上下文策略与摘要模型 SPI 契约

agent-persistence
└── 保持现有职责，不新增任何 LSM 专属持久化代码

agent-context-lsm
├── 依赖 agent-domain
├── 依赖 agent-persistence 中已有的会话、消息、execution 服务和通用持久化基础设施
├── 实现 LSM 上下文策略
├── 持有全部 LSM 应用逻辑、Entity、Mapper、Repository、migration 和测试
└── 不依赖 agent-web、agent-server 或具体模型供应商

agent-web
├── 继续依赖 agent-domain
├── 通过 SPI 调用选中的上下文策略
├── 保留现有完整历史加载逻辑作为 raw 策略
└── 提供 ContextSummaryModelPort 的 Ollama 适配器

agent-server
├── 依赖 agent-web
└── 通过 Maven 依赖决定是否把 agent-context-lsm 装入运行时
```

禁止 `agent-web`、`agent-server` 或 `agent-persistence` 引用 LSM 实现类。`agent-context-lsm` 也
不得反向依赖 `agent-web` 或 `agent-server`，避免形成循环依赖。

这是本能力对仓库默认“能力持久化统一放入 `agent-persistence`”规则的明确例外：为了满足用户
确认的可插拔边界，LSM 专属持久化与策略实现共同归属 `agent-context-lsm`；共享会话和 execution
事实源仍由 `agent-persistence` 负责。

### 5.2 领域 SPI

`agent-domain` 只增加与具体策略无关的稳定契约：

```java
public interface ConversationContextStrategy {
    String strategyId();

    PreparedConversationContext prepare(ConversationContextRequest request);
}

public interface ContextSummaryModelPort {
    ContextSummaryResult summarize(ContextSummaryRequest request);
}
```

必要的数据契约包括：

```text
ConversationContextRequest
PreparedConversationContext
ContextMessage
ContextBudget
ContextPolicySnapshot
ContextSummaryRequest
ContextSummaryResult
```

这些契约只表达模型上下文、预算和摘要请求，不出现 LSM level、segment、manifest 或 compaction
job。execution ownership、取消和事件发布需要跨模块协作时，同样通过最小领域端口暴露，不允许
插件导入 `agent-web` 服务。

### 5.3 策略发现与选择

不使用 Java `ServiceLoader`。策略需要 Spring 注入数据库服务、tenant context、模型端口、
ObjectMapper、指标和配置，因此采用“领域 SPI + Spring Bean 注册”。

`agent-web` 注入全部 `ConversationContextStrategy`，按 `data-agent.context.strategy` 精确选择：

- `strategyId` 重复时应用启动失败；
- 配置的策略不存在时应用启动失败；
- 一次上下文准备只允许一个策略处理；
- `raw` 策略由 `agent-web` 提供，保持当前不压缩的完整历史行为；
- `lsm` 策略只在 `agent-context-lsm` 位于运行时 classpath 时出现。

`agent-context-lsm` 的 Spring 配置使用 `data-agent.context.strategy=lsm` 条件装配。选择 `raw` 时，
即使插件 JAR 仍在 classpath，也不注册 LSM 策略、不运行插件 Flyway，且不访问 LSM 表。策略切换
通过重启服务生效，不支持运行中热切换。

这种接入方式允许通过移除 `agent-server` 对 `agent-context-lsm` 的依赖并切换到 `raw` 完成拔除，
也允许未来引入另一个实现相同 SPI 的策略模块。

### 5.4 新模块内部结构

```text
agent-context-lsm/
├── pom.xml
├── src/main/java/com/datagent/agentv2/context/lsm/
│   ├── configuration/
│   ├── strategy/
│   ├── planning/
│   ├── summary/
│   ├── frontier/
│   ├── manifest/
│   ├── persistence/
│   │   ├── entity/
│   │   ├── mapper/
│   │   ├── repository/
│   │   └── configuration/
│   └── observability/
├── src/main/resources/db/agent-context-lsm/migration/
└── src/test/
```

模块自行提供 `@ConfigurationProperties`、专属 `@MapperScan`、JSON Schema、Flyway 装配、单元
测试和数据库集成测试。

### 5.5 插件专属 Flyway

LSM migration 不放入 `agent-persistence`，也不放入主 Flyway 的 `db/migration`。插件使用：

```text
location = classpath:db/agent-context-lsm/migration
schema history table = flyway_agent_context_lsm_schema_history
```

插件 Flyway 与主 Flyway 使用同一 DataSource 和业务 schema，但拥有独立 migration history。
插件迁移必须在主 Flyway 完成后运行，因为 LSM 表需要引用现有会话表。迁移失败时应用启动失败，
不得在缺少 LSM 表的情况下注册 `lsm` 策略。

LSM 策略 bean 必须显式依赖插件 migration initializer；只有主 Flyway 和插件 Flyway 都成功后
才能完成该 bean 的创建，避免策略在表结构未就绪时被解析器选中。

移除插件 JAR 后，主 Flyway 不会因找不到插件 migration 而校验失败；已创建的插件表和 history
table 保留但不被使用。重新装入插件时，插件 Flyway 从自己的 history 继续迁移。

## 6. 服务配置与策略快照

### 6.1 服务配置

```yaml
data-agent:
  context:
    strategy: lsm
    compaction:
      fanout: 5
      max-level: 4
```

对应环境变量：

```text
DATA_AGENT_CONTEXT_COMPACTION_FANOUT
DATA_AGENT_CONTEXT_COMPACTION_MAX_LEVEL
```

策略配置对应环境变量：

```text
DATA_AGENT_CONTEXT_STRATEGY
```

`strategy` 支持 `raw` 和当前 classpath 中注册的其他策略 ID，未配置时使用 `raw`。生产启用本能力时配置为 `lsm`；
移除插件前必须先切换为 `raw` 或另一个已安装策略。

配置约束：

| 配置 | 默认值 | 合法范围 | 含义 |
| --- | ---: | ---: | --- |
| `strategy` | `raw` | 已注册策略 ID | 当前启用的上下文策略 |
| `fanout` | 5 | 3～10 | 同层多少个连续节点晋升为上一层节点 |
| `max-level` | 4 | 1～8 | 唯一活动 ROOT 所在的逻辑顶层 |

`fanout=2` 不受支持，以避免过于频繁的 LLM 合并。非法配置导致应用启动失败。

本期以下策略保持固定：

```text
baseSegmentTurns = 10
compressionThresholdPercent = 75
targetWatermarkPercent = 65
warmTargetTurns = 100
warmMaxTurns = 109
protectedHotTurns = 10
```

### 6.2 Execution 策略快照

每个 execution 开始时持久化完整策略快照：

```json
{
  "policyVersion": 1,
  "fanout": 5,
  "maxLevel": 4,
  "baseSegmentTurns": 10,
  "compressionThresholdPercent": 75,
  "targetWatermarkPercent": 65,
  "warmTargetTurns": 100,
  "warmMaxTurns": 109,
  "protectedHotTurns": 10
}
```

同一次 execution 的 ReAct 循环、retry、崩溃恢复、lease 接管、compaction 重试和 CAS
重新规划必须使用同一快照。新 execution 才读取最新服务配置。

## 7. LSM 层级模型

设 `F = fanout`，`M = maxLevel`：

```text
L0～L(M-1)：普通 Size-Tiered 层，每层最多 F-1 个活动节点
L(M)：唯一活动 ROOT
```

### 7.1 基础段与普通晋升

L0 正常覆盖 10 个完整问答，由原始 message 和全部 `thinkingProcess` 生成。Hot 之前不足
10 轮的内容作为 `pendingRawRange` 保留；硬预算需要时允许生成不足 10 轮的 forced L0，节点
记录真实范围及 `partialSegment=true`。

普通晋升规则：

```text
F × L(n) → 1 × L(n+1), 其中 0 ≤ n < M-1
```

只有时间相邻、层级相同、区间连续的节点才能合并。父节点按固定的从旧到新顺序读取来源。

### 7.2 顶层 ROOT

当 `F` 个 `L(M-1)` 节点成熟时：

```text
没有 ROOT：
F × L(M-1) → 新 ROOT

已有 ROOT：
旧 ROOT + F × L(M-1) → 新 ROOT
```

数据库可以保留多个历史 ROOT 版本，但 manifest 每个会话最多引用一个活动 ROOT。ROOT 创建后
不可修改；更新 ROOT 实际上是创建新节点并原子切换 manifest。

### 7.3 Cold Tail

等待正常 fanout 的过程中，ROOT 后面可以存在尚未成熟的 L0～L(M-1) 节点，它们构成
Cold Tail。每个普通层最多保留 `F-1` 个活动节点，所有层合计最多：

```text
M × (F - 1) + 1 个活动节点
```

最后的 `+1` 是 ROOT。

### 7.4 Warm 子树展开

Warm 边界可能落在高层节点内部。父节点的来源子节点继续不可变保存，因此上下文选择器可以将
父节点展开到子节点，直到边界与已有节点对齐。最细展开到 L0；Hot 直接读取原文。

展开只改变某次模型调用的 Input Frontier，不改变 manifest 的 Storage Frontier。

### 7.5 配置变更

历史节点按照创建时策略校验。每个节点记录 `creationPolicyVersion`、`creationFanout`、
`creationMaxLevel` 和 `sourceSegmentCount`。

- fanout 降低：下一次需要压缩时按新 fanout 合并最老的连续节点。
- fanout 提升：旧节点保持有效，未来等待更多同层节点再晋升。
- maxLevel 提升：旧 ROOT 继续有效，新中间层逐步形成；下一次 ROOT 更新使用新顶层。
- maxLevel 降低：不能反向恢复细节；下一次策略协调将旧 ROOT 和超过新顶层的可压缩 Cold
  Tail 合并为新顶层 ROOT。

配置变化不单独触发昂贵重建。输入未超过 75% 时允许继续使用旧 manifest；下一次进入
compaction 时再按 execution 策略快照协调。

## 8. Token 预算

### 8.1 两级预算

设：

```text
W = model context window
O = max output tokens
S = safety reserve
```

则：

```text
softTrigger = floor(W × 75%)
targetInput = floor(W × 65%)
hardInputLimit = W - O - S
```

当前 64K 配置示例：

```text
W = 65,536
softTrigger = 49,152
targetInput = 42,598
hardInputLimit = 57,344
```

### 8.2 完整输入估算

```text
estimatedInputTokens =
    fixedSystemTokens
  + toolDefinitionTokens
  + protocolOverheadTokens
  + rootTokens
  + coldTailTokens
  + selectedWarmTokens
  + pendingRawTokens
  + hotRawTokens
  + currentTurnTokens
```

Fixed 内容参与估算但永不压缩。估算必须覆盖 role/message 边界、summary wrapper、JSON、thinking、
tool call ID、tool 参数、tool observations 和 tool definitions。

### 8.3 触发规则

```text
estimatedInput ≤ softTrigger
→ 不启动新的 compaction

estimatedInput > softTrigger
→ 创建 compaction plan，目标降低到 targetInput

estimatedInput > hardInputLimit
→ 正常模型调用前必须压缩成功
```

如果超过 75% 完全由 Fixed、当前轮和受保护 Hot 导致，但尚未超过硬上限，则允许正常调用，
并在 manifest 记录已评估输入边界，防止同一边界重复压缩。

真实响应中的 `prompt_eval_count` 只用于安全指标和估算器校准，不能替代调用前检查。

## 9. Compaction 类型与规划顺序

### 9.1 常规 Size-Tiered Compaction

常规 compaction 由同层活动节点达到 fanout 触发，但只有在完整输入超过 75% 且生成父节点有助于
降低输入时才实际调用摘要模型。已存在且幂等键一致的父节点直接复用。

### 9.2 Token Pressure Major Compaction

如果等待正常 fanout 无法降到目标水位，可以执行 Major Compaction：

```text
旧 ROOT + ROOT 之后所有完全位于 Warm 之前的可压缩 Cold Tail
→ 新 ROOT
```

Major Compaction 约束：

- 只能吸收完全位于 Warm cutoff 之前的节点；
- 至少存在 3 个待合并节点，避免过于频繁；
- 预计至少降低 20% 的 Cold token；
- 输入按时间顺序组织；
- 输入过大时按完整摘要节点边界分批滚动，不截断节点；
- 每个中间 ROOT 只有最终成功发布后才成为活动状态。

### 9.3 一次计划的处理顺序

1. 使用 `inputUpToConversationMessageSequence` 固定输入快照。
2. 识别当前未完成轮和最近 10 个完整问答作为 Hot。
3. 将 Hot 之前每 10 个完整问答封为 L0，保留不足 10 轮的 pending raw。
4. 规划达到 fanout 的同层晋升。
5. Warm 超过 109 轮时，以 10 轮为批次向 Cold 迁移，直至回到约 100 轮。
6. 需要时规划 Major Compaction。
7. 生成或复用缺失摘要节点。
8. 选择新的 Input Frontier 并重新估算。
9. 若仍超过硬上限，强制封存 pending raw。
10. 若仍超过硬上限，从 Hot 最老完整问答开始降级压缩。
11. 当前未完成轮仍然超限时以 `CONTEXT_WINDOW_EXCEEDED` 失败。
12. 使用 CAS 原子发布新 manifest。

## 10. 摘要文档

### 10.1 结构

L0、普通父节点和 ROOT 使用相同的版本化结构：

```json
{
  "schemaVersion": 1,
  "coverage": {
    "startTurnSequence": 1,
    "endTurnSequence": 50,
    "startMessageSequence": 1,
    "endMessageSequence": 283
  },
  "confirmedFacts": [],
  "userPreferences": [],
  "workingHypotheses": [],
  "decisions": [],
  "rejectedApproaches": [],
  "toolFindings": [],
  "openQuestions": [],
  "nextSteps": []
}
```

持久化条目包含 statement 和来源 message sequence/range，用于审计和一致性检查。发送给正常
模型时使用精简投影，不发送数据库 ID、segment ID、SHA-256、生成模型、锁版本或完整来源数组。

### 10.2 语义规则

摘要模型可以读取 message 和全部历史 `thinkingProcess`，但输出必须：

- 只保留可复用的事实、假设、决策、排除路线、工具发现、问题和下一步；
- 合并旧摘要与新来源，不臆造来源中不存在的事实；
- 保留标识符、时间范围、指标口径、过滤条件、单位和工具失败信息；
- 删除被后续消息明确推翻的旧结论，只记录最终有效状态；
- 不输出逐字 thinking 或分析过程；
- 不包含 system prompt、工具定义或凭据；
- 只输出符合固定 JSON Schema 的文档。

### 10.3 大小上限

首版使用以下确定性上限：

```text
STANDARD L0：1,536 token
STANDARD L1：2,048 token
STANDARD L2：2,560 token
STANDARD L3：3,072 token
STANDARD L4 及以上：3,584 token
ROOT：4,096 token
```

摘要超限视为无效模型输出，不允许直接截断 JSON。maxLevel 改变不改变已有节点创建时适用的
上限；新节点按其 kind 和 level 使用上述上限。

## 11. 持久化模型

以下表、Entity、Mapper、Repository、JSON codec、CAS 查询和 migration 全部属于
`agent-context-lsm`。`agent-persistence` 不新增 LSM 类型或映射；插件只调用其已经公开的会话、
消息、execution 和通用租户服务。

### 11.1 `conversation_context_summary_segment`

保存不可变 L0～ROOT 节点，至少包含：

```text
summarySegmentId
tenantId
conversationSessionId
kind = STANDARD | ROOT
level
start/endTurnSequence
start/endMessageSequence
partialSegment
summaryDocument
estimatedTokenCount
sourceSegmentIds
sourceContentSha256
summarySchemaVersion
promptVersion
modelProvider/modelName
creationPolicyVersion
creationFanout
creationMaxLevel
sourceSegmentCount
createdAt
```

同一确定性 segment key 唯一。节点创建成功后不修改正文和覆盖范围。

### 11.2 `conversation_context_manifest`

每个租户会话只有一个当前 manifest：

```text
activeRootSegmentId
activeSegmentIds
pendingRawStart/end
warmCutoffTurnSequence
hotFromTurnSequence
evaluatedUpToMessageSequence
effectivePolicyVersion
effectiveFanout
effectiveMaxLevel
generation
version
```

`activeSegmentIds` 按覆盖区间排序，层级通过 segment 本身动态解析，不能为 L0～L8 分别建立固定
字段。ROOT、活动节点、边界和 generation 在同一事务中发布。

### 11.3 `conversation_context_compaction_job`

记录可恢复计划：

```text
compactionJobId
tenantId
conversationSessionId
executionId/executionAttempt
inputUpToMessageSequence
sourceManifestGeneration/version
policySnapshot
planDocument/planSha256
status
ownerInstanceId
failureCode
createdAt/completedAt
```

状态：

```text
PLANNED → GENERATING → READY → PUBLISHED
                    ↘ FAILED
                    ↘ CANCELLED
                    ↘ SUPERSEDED
```

plan 只保存区间、节点 ID、层级和 token 估算，不保存正文。

## 12. 幂等、事务与恢复

### 12.1 确定性节点键

节点幂等键由租户、会话、类型、层级、来源范围、来源内容指纹、来源节点、schema、prompt、
模型和策略版本共同计算。相同键已有成功节点时直接复用。

### 12.2 三阶段执行

1. **规划短事务**：校验 execution owner/attempt/lease/cancel，固定输入和 manifest，持久化 job，
   写入 `context_compaction_started`。
2. **事务外生成**：按计划调用无 tools 的摘要模型，逐个验证并保存不可变候选节点；每个节点最多
   两次尝试。
3. **发布短事务**：重新 fencing，校验区间和层级不变量，通过 manifest version/generation CAS
   发布，写入 `context_compaction_completed`。

候选节点在 manifest 发布前不进入活动上下文。

### 12.3 CAS 冲突与接管

- CAS 冲突时 job 标记为 `SUPERSEDED`，不得覆盖新 manifest。
- 基于最新 manifest 重新估算；仍需压缩时最多重新规划两次。
- 新 owner 接管后复用幂等键一致的候选节点，从第一个缺失节点继续。
- 旧 owner、旧 attempt 或已失去 lease 的实例不得发布。
- 配置变化不改变进行中 execution 的策略快照。

### 12.4 失败

- 单节点摘要失败或结构非法：重试一次。
- 两次失败：job=`FAILED`，execution 以 `CONTEXT_COMPACTION_FAILED` 失败。
- 用户取消：job=`CANCELLED`，不发布候选节点。
- 来源或 manifest 变化：job=`SUPERSEDED`，重新规划。
- 当前未完成轮单独超限：`CONTEXT_WINDOW_EXCEEDED`。
- 区间存在缺口或重叠：内部一致性失败，不静默遗忘历史。

任何失败都保留最后一份有效 manifest。

## 13. 模型输入组装

### 13.1 顺序

```text
1. 固定 system prompt
2. 固定包装的动态 conversation memory
   2.1 ROOT
   2.2 Cold Tail
   2.3 Warm Input Frontier
3. Hot 最近 10 个完整问答原文
4. 当前未完成问答
5. tool definitions（请求独立字段）
```

动态 memory wrapper 明确摘要只是历史事实、决策和任务状态，不能覆盖 system prompt、工具约束
或当前用户请求。

Hot 中普通 thinking 优先使用供应商支持的历史 thinking 字段；不支持时使用固定的 historical
reasoning 数据块，不伪装成 system message。tool call 和 observation 按原协议重建。

### 13.2 Storage Frontier 与 Input Frontier

- Storage Frontier 是 manifest 中活动的 ROOT 和普通节点。
- Input Frontier 是某次模型调用根据预算实际选择的节点，可以把活动父节点展开为已有子节点。

Input Frontier 必须按时间升序、连续、无重叠、无缺口，不越过本次消息输入边界，也不能同时
包含父节点和其后代。

### 13.3 选择策略

```text
reservedTokens = Fixed + Hot + 当前轮
memoryBudget = targetInput - reservedTokens
```

选择顺序：

1. ROOT 优先加入。
2. Cold Tail 使用当前最粗活动节点。
3. Warm 默认从 L0 和 pending raw 的最细表示开始。
4. 超出预算时从 Warm 最老区间开始用父节点替换完整子节点组。
5. 预算仍不足时依次处理较新的 Warm 区间。
6. 缺失必要父节点且完整输入超过 75% 时，将其加入 compaction plan。
7. 上下文组装本身不得隐式调用摘要模型。

## 14. 事件、日志与指标

使用契约已有事件：

- `context_compaction_started`
- `context_compaction_completed`
- `context_compaction_failed`

事件和日志只允许包含 job/segment ID、层级、覆盖序号、token 估算、耗时、重试次数和稳定错误码。

建议指标：

```text
agent_context_input_estimated_tokens
agent_context_utilization_ratio
agent_context_active_segments{level}
agent_context_compaction_total{result,type}
agent_context_compaction_duration_seconds{type}
agent_context_compaction_input_tokens{level}
agent_context_compaction_output_tokens{level}
agent_context_compression_ratio{level}
agent_context_estimation_error_ratio
agent_context_manifest_cas_conflicts_total
agent_context_window_exceeded_total
```

指标标签不得包含 tenant、session、execution 或 message ID。

## 15. 组件边界

`agent-context-lsm` 内部组件：

- `LsmConversationContextStrategy`：实现领域 SPI，是插件唯一业务入口。
- `ConversationTurnGrouper`：从 message 和 `thinkingProcess` 形成完整问答。
- `ConversationTokenEstimator`：估算完整输入、摘要输入和摘要输出。
- `ContextCompactionPlanner`：生成 L0、晋升、Warm 迁移、Major Compaction 和策略协调计划。
- `ContextSummaryGenerator`：通过 `ContextSummaryModelPort` 调用无 tools 的摘要模型并校验结构。
- `ContextManifestPublisher`：通过领域 execution control 端口执行 fencing，再完成 CAS 和原子发布。
- `WarmSegmentFrontierSelector`：在预算内选择连续、无重叠的 Input Frontier。
- `ConversationContextAssembler`：组装 Fixed、ROOT、Cold Tail、Warm、Hot 和当前轮。

`agent-web` 内部只保留：

- `ConversationContextStrategyResolver`：根据服务配置选择唯一策略；
- `RawConversationContextStrategy`：包装当前完整历史加载行为；
- `OllamaContextSummaryModelAdapter`：实现中立摘要模型端口；
- ReAct 接入点：调用选中策略并将领域 `ContextMessage` 映射为供应商模型消息。

正常 ReAct 引擎只依赖 `ConversationContextStrategy` 和领域数据契约，不理解 LSM 层级，也不引用
插件实现类。

## 16. 测试策略

### 16.1 配置与状态机

- 验证 `raw` 在没有插件 JAR 时仍可启动和执行。
- 验证配置 `lsm` 但插件未安装时启动失败。
- 验证重复 strategy ID 和未知 strategy ID 启动失败。
- 覆盖 fanout 3/5/10 和 maxLevel 3/4/5。
- 验证非法配置启动失败和 execution 策略快照固定。
- 使用固定随机种子模拟数万轮追加、配置变化和 compaction。
- 验证普通层最多 `fanout-1` 个活动节点、ROOT 最多一个。
- 验证首次超过 75% 前不会仅为构建层级调用摘要模型。
- 验证每次成功压缩发布后区间连续、Warm 100～109、Hot 10 和当前轮保护。

### 16.2 预算与 Frontier

- 精确验证 75%、75%+1、hard limit 和 hard limit+1。
- 验证目标水位 65% 和不可压缩内容导致的重复压缩抑制。
- 验证远粗近细、父子互斥、完整覆盖和确定性选择。
- 验证 Fixed 只计量不压缩，system/tool 每次保持一致。

### 16.3 摘要与模型网关

- L0 读取 message 和全部 thinking，更高层按时间读取子摘要。
- ROOT 正确合并旧 ROOT 和 Cold Tail。
- 摘要只保留结构化思路状态，不输出逐字 thinking。
- 验证 schema、覆盖范围、大小上限、两次重试和幂等复用。
- 摘要请求不携带 tools，不向正常回答流发布 delta。

### 16.4 持久化、并发和恢复

- 在 `agent-context-lsm` 内验证专属 Mapper 扫描和独立 Flyway history。
- 验证主 Flyway 先完成、插件 Flyway 后执行，且移除插件资源不影响主 Flyway 校验。
- 验证 LSM Entity、Mapper、Repository 和 migration 不出现在 `agent-persistence`。
- 验证租户隔离、不可变节点、唯一键和 manifest 乐观锁。
- 验证旧 owner、旧 attempt 和 CAS 冲突不能发布。
- 在 job 创建后、部分节点生成后、READY 后、发布前后注入崩溃。
- 验证新 owner 复用候选节点且不会重复破坏状态。
- 验证取消、失败、retry 和 Redis 不可用时的状态收敛。

### 16.5 安全

自动断言 message、thinking、摘要、tool payload、system prompt 和凭据不出现在日志、事件、
Problem Detail 或指标标签中。

## 17. 验收条件

1. 完整输入超过模型窗口 75% 才启动新的 compaction。
2. 压缩后以 65% 为目标水位，正常输入不超过硬上限。
3. Fixed system/tool 内容参与预算但永不压缩。
4. 最近 10 轮和当前轮优先保留原文，只有硬上限才从 Hot 最老完整问答降级。
5. LSM 按需物化；每次成功压缩发布后，Warm 在 100～109 轮之间按 10 轮批量迁移。
6. fanout 和 maxLevel 均由服务配置控制并固化到 execution。
7. 普通层最多 `fanout-1` 个活动节点，配置顶层只有一个活动 ROOT。
8. 摘要读取历史 thinking，但只保留结构化、可复用的思路状态。
9. ROOT、Cold Tail、Warm、Hot 和当前轮连续、无重叠、无遗漏。
10. 所有摘要失败、取消、接管和 CAS 冲突不破坏旧 manifest。
11. 原始 message 和 `thinkingProcess` 不修改、不删除。
12. 受影响模块测试和后端完整 `mvn verify` 通过。
13. `agent-context-lsm` 可从 `agent-server` 依赖中移除，`raw` 策略仍能启动和执行。
14. LSM 专属持久化代码和 migration 全部位于插件模块，`agent-persistence` 无 LSM 增量。
15. 新上下文策略可以只实现领域 SPI 并通过配置替换 LSM，而无需修改 ReAct 引擎。
