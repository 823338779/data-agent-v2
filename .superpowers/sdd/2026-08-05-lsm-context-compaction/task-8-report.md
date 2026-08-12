# Task 8：可恢复 Compaction Job 与原子 Manifest 发布报告

## 状态

已实现 LSM compaction 的规划短事务、事务外候选生成、发布短事务、崩溃恢复、候选精确 key 复用、owner/attempt/input/source manifest 联合 fencing 和有界重规划。

## RED / GREEN

- RED：先创建 `LsmCompactionCoordinatorTest`、`LsmManifestPublicationIntegrationTest`、`LsmCompactionRecoveryTest`，执行 brief 聚焦命令；`agent-context-lsm:testCompile` 因缺失 runtime coordinator 及 nested DTO 产生 6 个编译错误，符合预期。
- 聚焦 GREEN：三个测试类共 13 tests，0 failures，0 errors。包含真实 PostgreSQL publication CAS 5 tests、recovery 6 tests、coordinator 2 tests。
- 完整 GREEN：`-pl agent-context-lsm -am spotless:apply test` BUILD SUCCESS；`agent-domain` 4 tests、`agent-persistence` 20 tests、`agent-context-lsm` 66 tests 全通过。
- 最终自审修正后重新运行聚焦命令：13 tests 全通过，BUILD SUCCESS。

## 实现摘要

- `LsmCompactionCoordinator` 提供稳定 public nested records/enums；`CandidateInput` 包装 Task 7 `GenerationRequest`，并在构造时校验 target 唯一且与 planner targets 一一对应，正文只保留在内存中。
- job plan 仅持久化 planner 元数据、确定性 segment keys、coverage/source IDs 和来源正文 SHA-256，不保存正文。
- planning/publication 使用 `REQUIRES_NEW`；候选生成无事务，逐候选依赖数据库唯一 segment key 幂等保存。
- publication CAS 同时检查 execution ownership/cancel、job owner/attempt/input、source manifest generation/version、READY 状态和实际活动 segment 的覆盖/层级，再原子更新 manifest 与 job。
- recovery 覆盖 PLANNED、部分候选、READY、发布前、发布后；新 owner 可 claim 并从首个缺失 key 继续，READY 不重复调用模型。
- 两次 provider/结构失败后 job 进入 `FAILED`，execution 使用稳定 `CONTEXT_COMPACTION_FAILED`；异常不传播正文或 provider cause。
- CAS 冲突标记 `SUPERSEDED`，由 Task 9 assembler 提供 `ReplanProvider` 刷新最新 manifest/source/body，最多重新规划两次；runtime 不读取 web/history。

## 变更文件

- `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/runtime/*`
- `backend/agent-context-lsm/src/test/java/com/datagent/agentv2/context/lsm/runtime/*`
- `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/{json,mapper,repository}/*`
- `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java`

## 自审

- 真实 PostgreSQL 测试直接断言 stale owner/source、pre-CAS 和 post-CAS 故障均不改变旧 manifest；成功发布和 post-publish 恢复幂等。
- ownership loss 不会被包装成 summary failure；取消、FAILED、SUPERSEDED、PUBLISHED 均为显式终态。
- `spotless:apply` 对 `agent-persistence` 两个无关测试产生的机械改动已逐行恢复；最终产品变更只在 `agent-context-lsm`。
- `git diff --check` 无输出；无正文日志、事件或指标，无凭据和生成文件。

## Commit

- Message：`feat: publish recoverable context compactions`
- Commit：见本任务提交。

## Concerns

- Task 9 必须在 `ReplanProvider` 中从最新 manifest 和原始事实源重建完整 `CompactionRequest`；当前 Task 8 刻意不自行读取 web/history。
- Mockito 的动态 agent 警告来自既有测试基础设施，不影响测试结果。
