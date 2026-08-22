# 仓库规则

- 后端代码位于 `backend/`，保持 Maven 模块独立且可从父 POM 构建。
- `agent-persistence` 统一承载通用上下文、ID、时钟、MyBatis-Plus 基类、配置、迁移，以及各能力的 Entity、Mapper 和 `ServiceImpl`；其他模块不得直接引用 Mapper 或 MyBatis API。
- 当前阶段不设计认证和租户隔离；运行时统一使用 `TenantContext`、`OperatorContext` 中的固定租户与固定身份常量，后续另行重做该能力。
- `agent-web` 统一承载 REST/SSE 接口、BFF、对话应用逻辑和接口契约测试。
- `agent-server` 是唯一可执行模块，负责启动、服务级全局拦截器、异常边界和运行时装配，不承载业务 HTTP 门面或对话业务逻辑。
- `agent-evals` 是独立验证模块，不得成为运行时模块的依赖。
- 不要提交凭据、令牌、连接字符串或本地生成文件。
- 变更后先运行与修改范围匹配的 Maven 验证命令。
- 保持修改范围最小，并遵循现有 POM 和代码风格。
- 你可以随时调整module架构、增加module、调整module都可以
