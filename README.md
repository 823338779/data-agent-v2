# Data Agent V2

Data Agent V2 是独立版本化的重建仓库。当前包含后端 Maven 多模块工程、REST/SSE 契约、完整性清单以及从 V1 当前文件快照迁移的前端；旧 `data-agent` 仅作为已冻结行为的提取来源，不是本仓库的运行时或测试依赖。

## 目录

- `backend/agent-persistence`：数据库迁移、MyBatis-Plus、审计持久化，以及 ID、时钟和请求上下文基础能力
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

单实例联调分别启动：

```powershell
# 终端 1：V2 后端
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run

# 终端 2：V2 前端
cd .\frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`，使用本地初始化账号 `admin/admin` 登录。当前可联调认证、多会话未读与排序、执行取消/重试、Ollama reasoning/answer 流以及 Redis 跨实例通知。

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
- reasoning 默认保留 30 天（写入时设置 `reasoning_expires_at`），可通过 `DATA_AGENT_REASONING_RETENTION` 调整；清理任务每小时分页运行，批量与轮数由 `DATA_AGENT_REASONING_RETENTION_BATCH_SIZE`、`DATA_AGENT_REASONING_RETENTION_MAX_BATCHES` 控制。

## 外部双实例验收

`agent-evals` 不依赖 `agent-web`、`agent-persistence` 或其他产品运行时模块，只使用 JDK HTTP client 和公开 JSON 契约。先启动共享 PostgreSQL/Redis/Ollama 以及 8080/8081 两个实例，再执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-evals `
  '-Ddata.agent.acceptance.enabled=true' `
  '-Ddata.agent.acceptance.instance-a=http://127.0.0.1:8080' `
  '-Ddata.agent.acceptance.instance-b=http://127.0.0.1:8081' test
```

验收会通过实例 B 创建并提交 4 个不同会话，从实例 A 观察“最多 3 running、其余 queued”，并验证 mark-read 与乐观重排跨实例可见。故障注入验收（在第 3 个工具边界终止实例、Redis 断开/恢复、Outbox 补发及人为到期 reasoning）需要部署环境提供进程和基础设施生命周期控制；产品正确性由 `agent-server` 中相应的双实例、恢复、Redis 降级和保留策略集成测试覆盖。

前端生产构建：

```powershell
cd .\frontend
npm.cmd run build
```
