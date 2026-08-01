# 仓库规则

- 后端代码位于 `backend/`，保持 Maven 模块独立且可从父 POM 构建。
- `agent-persistence` 统一承载通用上下文、ID、时钟、MyBatis-Plus 基类、配置、迁移，以及各能力的 Entity、Mapper 和 `ServiceImpl`；其他模块不得直接引用 Mapper 或 MyBatis API。
- 认证代码进入 `agent-auth`，通过 `agent-persistence` 提供的服务和通用上下文协作。
- `agent-web` 统一承载 REST/SSE 接口、BFF、认证安全链、对话应用逻辑和接口契约测试。
- `agent-server` 是唯一可执行模块，负责启动、服务级全局拦截器、异常边界和运行时装配，不承载业务 HTTP 门面、认证或对话业务逻辑。
- `agent-evals` 是独立验证模块，不得成为运行时模块的依赖。
- 不要提交凭据、令牌、连接字符串或本地生成文件。
- 变更后先运行与修改范围匹配的 Maven 验证命令。
- 保持修改范围最小，并遵循现有 POM 和代码风格。
- 你可以随时调整module架构、增加module、调整module都可以
