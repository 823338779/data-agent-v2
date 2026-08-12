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

## Fix Round 1（2026-08-12）

### RED / GREEN

- RED：Task 7 generator 生成的 key 长度为 79，真实 PostgreSQL 插入明确失败：`value too long for type character(64)`；同时单元测试断言实际长度 79 而不是 64。
- GREEN：segment key 改为裸 SHA-256 的 64 位小写十六进制值；prompt version 仍参与 hash material。真实 PostgreSQL 路径验证 generator 输出可插入，且同 key 重试只保留一行。
- RED：两层级联经真实 PostgreSQL repository 落库后，父段 lineage 为 `planned-child`，而子段真实 ID 为 `context_summary_segment-1`。
- GREEN：候选写库前按 planner 顺序把已生成或复用的 target ID 解析为真实 segment ID，并拒绝写入尚未解析的 `planned-*`；父段 lineage、来源数量及两行数据库记录均已验证。
- RED：restart canonical roundtrip 测试因 codec 不具备 plan 反序列化再 canonical 输出能力而编译失败。
- GREEN：`segmentKeys` 和 `sourceContentHashes` 使用有序不可变 map；不同插入顺序、重新创建 codec 后反序列化再输出均得到相同 JSON 和 SHA-256，planner list 顺序保持不变。

### 验证

- 聚焦验证：generator、真实 PostgreSQL repository、recovery、codec 共 25 tests，全部通过。
- 完整验证：`mvn -pl agent-context-lsm -am test` 最终 BUILD SUCCESS；`agent-domain` 4 tests、`agent-persistence` 38 tests、`agent-context-lsm` 69 tests，全部通过。
- 完整验证首轮遇到既有 `MybatisPlusAuditIntegrationTest` 的 PostgreSQL 微秒精度边界偶发失败；未修改该测试，原样单独重跑通过，随后完整 reactor 重跑通过。

### Task 9 事务约束

- `LsmManifestPublicationTransaction.publish` 已在同一个 `REQUIRES_NEW` 事务内依次调用 execution ownership/budget/cancel 检查、manifest CAS、job 状态迁移与 lifecycle publish。
- 当前端口契约不扩展。Task 9 的 `ContextExecutionControlPort` 数据库 adapter 必须让 ownership/cancel/fence 的读取或锁定参与上述同一数据库事务，并通过行锁或等价 fence 保持到 manifest CAS 完成；若 adapter 使用独立事务、事务外读取或仅内存检查，检查与 CAS 之间仍存在 TOCTOU 窗口。
