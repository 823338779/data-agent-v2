# Task 9 实施报告

## 状态

已完成 LSM 上下文策略、模型输入组装、ReAct 接入、provider 历史 thinking 映射，以及 execution fencing、事件和稳定失败收敛。

## 主要变更

- `LsmConversationContextStrategy` 在任何预算判断前先创建或读取 execution policy 快照，超过 75% 时调用既有 compaction coordinator，否则保持 raw 输入。
- `LsmConversationContextAssembler` 按持久化消息上界重建问答、thinking、tool call/observation，组装固定 system、不可伪装为指令的 memory wrapper、活动摘要、Hot 和当前轮；硬上限下只从最老完整 Hot 开始降级，当前未完成轮仍超限时以 `CONTEXT_WINDOW_EXCEEDED` 收敛。
- ReAct 每次模型调用均通过 `ConversationContextStrategyResolver`，并固定 persisted input boundary、当轮 tools 和 provider model budget。
- Ollama 使用原生 historical thinking 字段；Codex 使用固定 `historicalReasoning` 数据投影。
- `WebContextExecutionControlAdapter` 在 publication 的 `REQUIRES_NEW` 事务内通过 `SELECT FOR UPDATE` 校验 owner/attempt/deadline，使锁保持到 manifest CAS 提交；compaction 事件 payload 固定为空对象，不携带正文。
- LSM Flyway 和策略 bean 仅在 `data-agent.context.strategy=lsm` 时启用，raw 模式保持原启动行为。

## TDD 与验证

- RED：focused 测试最初因缺少 `LsmConversationContextAssembler` 无法编译。
- GREEN：`LsmConversationContextStrategyTest` 4 项通过，覆盖 policy-first/no-op、75% 以上压缩、最老完整 Hot 硬上限降级、当前轮稳定失败。
- GREEN：`ReactExecutionEngineIntegrationTest` 通过，验证每次模型调用均选择策略并传递固定边界、tools 和 budget。
- GREEN：`LsmContextAutoConfigurationTest`、`AgentV2ApplicationTest`、LSM 策略与 ReAct 配置回归共 15 项通过。
- GREEN：`mvn -pl agent-context-lsm,agent-web,agent-server -am test` 完整 reactor 7 个模块全部成功，退出码 0，总耗时 6 分钟。

## 已知事项

- 全 reactor `spotless:check` 仍被两个 Task 9 之前已存在的 `agent-persistence` 测试格式偏差阻断：`Phase1MigrationTest`、`UuidTypeHandlerTest`。未把这些无关格式改动纳入本任务；Task 9 文件已执行 `spotless:apply`，`git diff --check` 通过。
- Maven 输出包含 Mockito 动态 agent 与测试容器关闭阶段的既有告警，不影响测试结果。

## Fix round 1（2026-08-12）

### 修复内容

- Higher/ROOT 候选输入改为严格按 `operation.sourceIds` 读取直接子摘要，并编码为按旧到新排列的结构化 JSON 文档数组；L0 仍只消费 raw message。对本计划内尚未持久化的子节点，恢复流程在子摘要生成后再解析父节点输入，避免向上层重放 raw source。
- Input Frontier 从 Storage Frontier 展开：ROOT 固定在最前，Cold 使用最粗连续覆盖，Warm 复用 `WarmSegmentFrontierSelector` 按预算选择“远粗近细”且无父子重叠的连续覆盖，随后才是尚未汇总的 Warm raw、Hot 与当前轮。选择器同时兼容持久化 L0 携带 raw source ID 的实际数据形态。
- 硬上限回退改为按当前选择列表中的 turn 消息对象位置删除最老完整 turn，不再依赖 `ContextMessage.equals`，相同文本的历史轮与当前轮不会互相误删。
- manifest 活动摘要读取增加 exact ID/order/size 与连续 coverage 校验；缺失、重复、乱序或 coverage 断裂统一以稳定内部一致性错误失败。
- 新增 `provider=codex` 的 `ContextSummaryModelPort` adapter，复用 `CodexAppServerClient`，以空 tools 调用并忽略 delta，只接受 final answer；请求显式携带摘要规则、source document、Task 7 schema 与最大输出 token，provider 异常转换为不泄露正文和 cause 的稳定错误。

### TDD 与验证

- RED：新增 5 组回归覆盖 Higher/ROOT 禁止 raw replay、Warm budget frontier、相同消息内容的 hard fallback、manifest 缺失/不连续、Codex adapter 激活与安全错误；实现前对应断言失败或 adapter 类缺失。
- GREEN：focused 组合测试通过：`agent-context-lsm` 16/16，`agent-web` 3/3，合计 19/19。
- GREEN：`mvn -pl agent-context-lsm,agent-web -am test` 完整受影响 reactor 通过：共 176 项测试，0 failure、0 error、2 skipped；退出码 0，总耗时 1 分 7 秒。
- 格式：受影响模块已执行 `spotless:apply`，无关的 `ConversationEventStreamFacade` 格式改动已恢复；`git diff --check` 通过。全 reactor `spotless:check` 仍只受上文所列两个既有 `agent-persistence` 文件阻断。
