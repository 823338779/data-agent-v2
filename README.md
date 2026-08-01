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

先准备 PostgreSQL 数据库 `data_agent_v2` 和 Redis。PostgreSQL 默认本地连接参数为 `data_agent/data_agent_local_dev`，可通过 `DATA_AGENT_DB_URL`、`DATA_AGENT_DB_USERNAME`、`DATA_AGENT_DB_PASSWORD` 覆盖；Redis 默认连接 `localhost:6379`，用户名和密码为 `default/redis_local_dev`，可通过 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD` 覆盖。然后分别启动：

```powershell
# 终端 1：V2 后端
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run

# 终端 2：V2 前端
cd .\frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`，使用本地初始化账号 `admin/admin` 登录。当前可联调认证、会话创建/列表/详情和消息提交；Ollama 逐 Token SSE、Redis 跨节点通知尚在阶段 1 后续切片中实现。

前端生产构建：

```powershell
cd .\frontend
npm.cmd run build
```
