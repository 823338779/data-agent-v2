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
