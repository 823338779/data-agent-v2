# 仓库规则

- 后端代码位于 `backend/`，保持 Maven 模块独立且可从父 POM 构建。
- `agent-platform` 只承载与存储技术无关的跨业务基础能力，不得依赖持久化、认证、对话或服务模块。
- `agent-persistence` 统一承载 MyBatis-Plus 基类、配置、迁移，以及各能力的 Entity、Mapper 和 `ServiceImpl`，只依赖 `agent-platform`；业务模块不得直接引用 Mapper 或 MyBatis API。
- 认证代码进入 `agent-auth`，对话、Redis 和 Ollama 代码进入 `agent-conversation`；两者只能通过平台抽象协作，不得互相添加 Maven 依赖。
- `agent-server` 是唯一可执行模块，只负责启动、全局 HTTP 边界和运行时装配，不承载认证或对话业务逻辑。
- `agent-contracts` 与 `agent-evals` 是独立验证模块，不得成为运行时模块的依赖。
- 不要提交凭据、令牌、连接字符串或本地生成文件。
- 变更后先运行与修改范围匹配的 Maven 验证命令。
- 保持修改范围最小，并遵循现有 POM 和代码风格。
- 你可以随时调整module架构、增加module、调整module都可以
