# Task 11 实施报告：安装 hierarchical 插件并完成宿主验收

## 结果

Task 11 已完成。`agent-server` 现在安装 `agent-context-hierarchical`，可通过 `DATA_AGENT_CONTEXT_STRATEGY=raw|lsm|hierarchical` 在三个互斥策略中选择。宿主集成测试证明 raw 不运行任一插件迁移，LSM 和 hierarchical 各自只运行私有迁移，未知策略与重复策略 ID 均启动失败。

Hierarchical 主链路使用真实 PostgreSQL、主 Flyway、插件 Flyway、宿主 execution control 和插件运行时完成 121 轮会话压缩。夹具包含中英文正文、持久化 thinking、与 durable `tool_execution` 对齐的工具调用及 observation；验证了 75%+1 触发、压缩后低于 70%、最近 10 轮 Hot 原样保留、Warm 无缝无重叠、超过 100 轮后 Cold 指针前进、原始消息和 `thinking_process` 不变，以及固定 system framework 和 tool definition 保留。

PLANNED、GENERATING、READY_TO_PUBLISH 三个 durable 崩溃边界均通过真实租约接管收敛到 generation 1；旧 owner 在新 owner 发布前被 execution fence 拒绝。两个租户使用完全相同的 session 业务 ID 时，policy、checkpoint、node、job 均保持隔离。

## TDD 与红灯证据

### RED 1：安装前 hierarchical 在 server 不可用

先创建四个宿主集成测试，再运行 brief 指定命令。`agent-server` 测试编译按预期失败，原因是 server 尚未依赖 `agent-context-hierarchical`，hierarchical 类型和自动配置不在其测试 classpath。随后才在 `agent-server/pom.xml` 安装插件依赖。

### RED 2：两插件 schema 资源发生 classpath 碰撞

安装插件后的真实宿主链路首先以 `Invalid hierarchical context summary document` 失败。诊断确认 LSM 与 hierarchical 都发布 `/schema/context-summary-v1.schema.json`；在 server 同时安装两插件时，hierarchical validator 实际读取了先出现的 LSM schema，因此一个合法的 hierarchical coverage 文档被错误拒绝。

修复把 hierarchical 资源改为插件唯一名称 `/schema/hierarchical-context-summary-v1.schema.json`，并同步 validator 常量。未放宽 schema、coverage、token 或 durable publication 校验。该缺陷只有两插件进入同一宿主 classpath 才能复现，单插件 180 项回归无法覆盖。

### RED 3：同 session ID 跨租户被旧全局唯一键阻止

跨租户测试最初在创建第二个会话时失败，PostgreSQL 报 `uq_conversation_session_conversation_session_id` 重复键。该全局唯一键与仓库既有 tenant-scoped 会话关系、Task 11 的相同 session ID 隔离要求冲突。

新增主迁移 V17，只删除全局 `conversation_session_id` 唯一约束，保留 `uq_conversation_session_tenant_conversation_session_id`。测试同时从 PostgreSQL 元数据断言：

- `(tenant_id, conversation_session_id)` 复合唯一约束仍存在；
- 所有直接引用 `conversation_session` 的外键仍同时引用 `tenant_id` 与 `conversation_session_id`；
- 两租户的相同 session ID 可以共存，四类 hierarchical 私有仓储读取不越界。

V17 已按原始字节复核为 UTF-8 中文注释，注释末尾是 LF，`alter table` 位于独立下一行，不会被 `--` 吞掉。

### 夹具纠正

宿主 execution 的失败收敛要求预建唯一 assistant 中心消息；测试夹具补齐了真实提交事务会创建的占位消息。确定性摘要 provider 的 token 报告也调整为高于本地逐字符加标点的保守估算、低于 1024 节点上限的 512；生产 token 门禁保持不变。恢复夹具使用实际列名 `execution_event_sequence`。

## 主要实现

### 宿主安装与策略隔离

- `agent-server` 增加 hierarchical Maven 依赖；`src/main` 未直接导入插件实现包。
- `ContextStrategyIsolationIntegrationTest` 分别启动 raw、lsm、hierarchical、unknown 和 duplicate 上下文，并以策略 resolver 与数据库元数据验证选择和迁移隔离。
- raw 回滚不删除或读取插件数据；再次选择原插件时可继续使用保留的私有表。

### 主链路、恢复与租户隔离

- 主链路验证一对 body-free STARTED/COMPLETED 事件、严格 70% 目标、Hot/Warm/Cold 边界、完整工具/thinking 来源和 raw 行摘要不变。
- 恢复测试先通过真实链路生成 durable plan/candidates，再分别回放三个崩溃状态；takeover 复用候选，不重复调用模型，不产生第二个 generation。
- 多租户测试同时验证冻结 policy 不受后续服务参数变化影响，并覆盖 checkpoint/node/job/policy 的双向越界读取。

### 运维文档

README 已补充：

- `hierarchical` 策略与四个环境变量的默认值和约束；
- LSM 75%/65% 与 hierarchical 75%/70%、Hot 10、Warm 100、Cold 推进语义；
- hierarchical 私有迁移位置、独立 Flyway 历史表和表名前缀；
- 全实例统一切换、raw 回滚保留数据、execution frozen policy 不随服务配置变化；
- hierarchical 与既有 LSM 的可复现端到端 Maven 命令。

## 验证证据

Task 11 与既有 LSM 联合回归：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=ContextStrategyIsolationIntegrationTest,HierarchicalContextCompactionIntegrationTest,HierarchicalContextCompactionRecoveryIntegrationTest,HierarchicalContextCompactionMultiTenantIntegrationTest,LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest,LsmContextCompactionMultiTenantIntegrationTest' test
```

结果：`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`，reactor `BUILD SUCCESS`。

插件完整回归、格式和差异检查在提交前重新执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-context-hierarchical -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am spotless:check `
  '-DspotlessFiles=<Task 11 涉及的 Java 文件>'
git diff --check
```

结果：hierarchical 插件完整回归 `Tests run: 180, Failures: 0, Errors: 0, Skipped: 0`，依赖模块迁移测试同时通过，reactor `BUILD SUCCESS`；Task 11 涉及的 Java 文件定向 Spotless 检查 `BUILD SUCCESS`；`git diff --check` 通过。

全 reactor、不限定文件的 Spotless 检查另命中既有且未被本任务修改的 `agent-persistence/typehandler/UuidTypeHandlerTest.java` 格式偏差。为避免把无关格式变化混入提交，本任务未修改该文件；因此格式结论限定为本次变更文件。

## 边界说明

- 未修改 OpenAPI、前端或外部产品仓库。
- 未删除或重写既有测试；新增回归均通过真实宿主/数据库边界。
- 未修改本任务 ledger `progress.md`，未更新根工作区子模块指针。
