# Data Agent V2

Data Agent V2 是独立版本化的重建仓库。当前包含后端 Maven 多模块工程、REST/SSE 契约、完整性清单以及后续前端迁移目录；旧 `data-agent` 仅作为已冻结行为的提取来源，不是本仓库的运行时或测试依赖。

## 目录

- `backend/agent-contracts`：阶段 0 的 REST、SSE 与完整性契约测试
- `backend/agent-platform`：ID、时钟、租户与操作者上下文
- `backend/agent-persistence`：数据库迁移、MyBatis-Plus 与审计持久化约定
- `backend/agent-auth`：认证、登录会话与操作者上下文
- `backend/agent-conversation`：会话、消息、运行、流式事件、Redis 与 Ollama 编排
- `backend/agent-server`：唯一可执行模块，负责 Spring Boot 启动与运行时装配
- `backend/agent-evals`：独立评测模块
- `contracts`：REST、SSE、示例与完整性清单
- `docs`：仓库内设计和验证说明
- `frontend`：阶段 1 迁移的前端快照

## 验证

在仓库根目录执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-contracts test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-persistence -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

契约来源、权威边界和独立验证要求见 [docs/contracts.md](docs/contracts.md)。
