# Data Agent V2

Data Agent V2 是独立版本化的重建仓库。当前包含后端 Maven 多模块工程、REST/SSE 契约、完整性清单以及从 V1 当前文件快照迁移的前端；旧 `data-agent` 仅作为已冻结行为的提取来源，不是本仓库的运行时或测试依赖。

## 目录

- `backend/agent-persistence`：数据库迁移、MyBatis-Plus、审计持久化，以及 ID、时钟和请求上下文基础能力
- `backend/agent-context-lsm`：可插拔的 LSM 会话上下文压缩策略、独立持久化、迁移、恢复和指标
- `backend/agent-auth`：认证、登录会话与操作者上下文
- `backend/agent-web`：REST/SSE 接口、BFF、认证安全链、会话编排与契约测试
- `backend/agent-server`：唯一可执行模块，负责 Spring Boot 启动、服务级全局拦截器、异常边界与运行时装配
- `backend/agent-evals`：独立评测模块
- `contracts`：REST、SSE、示例与完整性清单
- `docs`：仓库内设计和验证说明
- `frontend`：阶段 1 迁移的前端快照

## 验证

在仓库根目录执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -Dtest='com.datagent.agentv2.contracts.*Test' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

契约来源、权威边界和独立验证要求见 [docs/contracts.md](docs/contracts.md)。

## 本地前后端联调

本地开发使用 Vite 同源代理：浏览器只访问前端域名，`/api`（包括 SSE）由 Vite 转发到 V2 后端，因此 HttpOnly Cookie 可以正常工作。默认后端地址是 `http://127.0.0.1:8080`；需要切换实例或 Nginx 时，将 `frontend/.env.example` 复制为 `frontend/.env.local` 并修改 `VITE_BACKEND_ORIGIN`。

先准备 PostgreSQL 数据库 `data_agent_v2`、Redis 和 Ollama。两个后端实例必须连接同一 PostgreSQL 与 Redis：

- PostgreSQL 默认连接参数为 `data_agent/data_agent_local_dev`，可通过 `DATA_AGENT_DB_URL`、`DATA_AGENT_DB_USERNAME`、`DATA_AGENT_DB_PASSWORD` 覆盖。
- Redis 默认连接 `localhost:6379`，用户名和密码为 `default/redis_local_dev`，可通过 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD` 覆盖。
- Ollama 默认使用 `qwen3.5:9b`。先执行 `ollama pull qwen3.5:9b`，再通过 `SPRING_AI_OLLAMA_BASE_URL` 指定服务地址；模型、超时和最大输出分别可由 `DATA_AGENT_MODEL_OLLAMA_MODEL`、`DATA_AGENT_MODEL_OLLAMA_TIMEOUT`、`DATA_AGENT_MODEL_OLLAMA_MAX_OUTPUT_TOKENS` 覆盖。

### 会话上下文策略与 LSM 压缩

会话上下文默认使用 `raw` 策略；只有显式选择 `lsm` 时才装载 LSM 插件、执行插件迁移并启用历史摘要压缩。

| 环境变量 | 默认值 | 允许值或范围 | 说明 |
| --- | --- | --- | --- |
| `DATA_AGENT_CONTEXT_STRATEGY` | `raw` | `raw`、`lsm` | 选择服务实例使用的会话上下文策略；取值区分大小写，未知值会使服务启动失败。 |
| `DATA_AGENT_CONTEXT_COMPACTION_FANOUT` | `5` | `3`～`10` | LSM 同层普通节点达到该数量时执行层级合并；仅在 `lsm` 策略下校验和生效。 |
| `DATA_AGENT_CONTEXT_COMPACTION_MAX_LEVEL` | `4` | `1`～`8` | LSM 摘要树的配置顶层，顶层 ROOT 始终最多保留一个活动节点；仅在 `lsm` 策略下校验和生效。 |

`fanout` 和 `max-level` 是服务级配置。一次执行首次进入 LSM 上下文流程时会冻结策略快照；该执行后续的重试、恢复和租约接管继续使用同一快照，不会被运行期间的服务配置变化改写。

需要回滚到原始上下文行为时，应在所有实例中统一设置：

```powershell
$env:DATA_AGENT_CONTEXT_STRATEGY = 'raw'
```

随后重启所有后端实例。`raw` 模式不会装载 LSM 策略、不会运行插件 Flyway，也不会读取或写入 `conversation_context_lsm_*` 表。回滚不会删除已有的 LSM 表、摘要段、manifest 或 job；这些数据保持不变，可供问题分析或后续重新启用插件使用。

选择 `lsm` 时，主 Flyway 会先完成通用数据库迁移，插件随后从 `classpath:db/agent-context-lsm/migration` 执行迁移，并使用独立历史表 `flyway_agent_context_lsm_schema_history`。插件迁移只管理 `conversation_context_lsm_*` 对象，不污染主 Flyway 历史；`raw` 模式不会触发该迁移。

LSM 上下文端到端验证命令：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=LsmContextCompactionIntegrationTest,LsmContextCompactionRecoveryIntegrationTest,LsmContextCompactionMultiTenantIntegrationTest' test
```

### 使用本机 Codex 登录态

没有 OpenAI API key、但已通过 ChatGPT 订阅登录 Codex 时，可以让 V2 通过本机 `codex app-server` 调用模型。先确认当前运行后端的系统用户能够执行：

```powershell
codex login status
```

输出包含 `Logged in using ChatGPT` 后，在启动后端前设置：

```powershell
$env:DATA_AGENT_MODEL_PROVIDER = 'codex'
```

该模式不读取或保存 OpenAI API key。每次模型调用使用临时、只读、禁止审批的 Codex thread；Codex 仅能选择 V2 注册的领域工具，真正的工具执行、持久化和租户隔离仍由 V2 ReAct 执行器完成。可选配置包括 `DATA_AGENT_MODEL_CODEX_EXECUTABLE`、`DATA_AGENT_MODEL_CODEX_MODEL`、`DATA_AGENT_MODEL_CODEX_EFFORT`、`DATA_AGENT_MODEL_CODEX_TIMEOUT` 和 `DATA_AGENT_MODEL_CODEX_WORKING_DIRECTORY`。部署到服务器或容器时，必须在运行后端的同一系统用户和环境中单独完成 Codex 登录；个人 ChatGPT 登录态不应复制到共享服务。

### 内置 ReAct 验证工具

服务启动后会自动注册三个无凭据的轻量工具：`system_timezone` 返回服务端 JVM 时区，`current_time` 返回同一时钟快照中的绝对时间和本地时间，`web_search` 通过 MediaWiki Action API 检索中文 Wikipedia 并返回最多 5 条标题、纯文本摘要和页面链接。网页检索仅用于验证工具调用链路，不是通用浏览器或全网搜索服务，运行环境需要能够访问 `https://zh.wikipedia.org`。

可以使用“先获取服务端时区，再获取当前时间，然后检索中文 Wikipedia 中的 ReAct，最后汇总三步结果”作为验证提示。模型应经历至少三轮调用：选择工具、读取持久化 observation、继续选择工具，最后生成回答。

单实例联调分别启动：

```powershell
# 终端 1：V2 后端
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run

# 终端 2：V2 前端
cd .\frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`，使用本地初始化账号 `admin/admin` 登录。当前可联调认证、多会话未读与排序、执行取消/重试、Ollama answer 流以及 Redis 跨实例通知。

## 双实例运行与收敛参数

两个进程使用不同 HTTP 端口即可；工作实例 ID 会由每个进程独立生成。以下命令需在两个 PowerShell 终端中分别执行：

```powershell
# 实例 A
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run '-Dspring-boot.run.arguments=--server.port=8080'

# 实例 B
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'
```

运行时默认值及其含义：

- 每个实例最多执行 256 个并发 Run，由 `DATA_AGENT_MAX_CONCURRENT_EXECUTIONS_PER_INSTANCE` 调整；同一用户最多 3 个 Run 同时处于 running。
- 队列领取和控制降级轮询默认 1 秒；详细会话 SSE 的 PostgreSQL reconciliation 默认 1 秒，可通过 `DATA_AGENT_SESSION_EVENT_RECONCILE_INTERVAL` 调整。
- 用户级 activity SSE 默认每 2 秒从 PostgreSQL reconciliation，可通过 `DATA_AGENT_ACTIVITY_RECONCILE_INTERVAL` 调整。Redis 只负责低延迟通知，断开时以上轮询仍保证最终收敛。

## 外部双实例验收

`agent-evals` 不依赖 `agent-web`、`agent-persistence` 或其他产品运行时模块，只使用 JDK HTTP client 和公开 JSON 契约。先启动共享 PostgreSQL/Redis/Ollama 以及 8080/8081 两个实例，再执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-evals `
  '-Ddata.agent.acceptance.enabled=true' `
  '-Ddata.agent.acceptance.instance-a=http://127.0.0.1:8080' `
  '-Ddata.agent.acceptance.instance-b=http://127.0.0.1:8081' test
```

验收会通过实例 B 创建并提交 4 个不同会话，从实例 A 观察“最多 3 running、其余 queued”，并验证 mark-read 与乐观重排跨实例可见。故障注入验收（在第 3 个工具边界终止实例、Redis 断开/恢复及 Outbox 补发）需要部署环境提供进程和基础设施生命周期控制；产品正确性由 `agent-server` 中相应的双实例、恢复和 Redis 降级集成测试覆盖。

前端生产构建：

```powershell
cd .\frontend
npm.cmd run build
```
