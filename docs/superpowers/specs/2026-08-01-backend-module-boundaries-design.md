# Data Agent V2 后端模块边界设计

## 背景与目标

阶段 1 已在 `agent-server` 中建立启动边界、数据库迁移和 MyBatis-Plus 基础能力。认证、对话、Redis、SSE 和 Ollama 即将进入实现，如果继续放入同一个模块，编译依赖和职责边界会快速混杂。

本次调整将后端组织为六个 Maven 模块。它仍是一个模块化单体，只生成并部署一个 `agent-server` 服务，不增加服务发现、内部 RPC 或独立部署单元。

## 方案选择

采用按业务能力拆分的六模块方案：

1. `agent-contracts`：保存阶段 0 契约验证测试。
2. `agent-platform`：保存跨功能的 ID、上下文、时钟、审计、MyBatis-Plus 配置和 Flyway 迁移。
3. `agent-auth`：保存用户、成员关系、登录会话、认证应用服务、安全过滤器和认证 HTTP 接口。
4. `agent-conversation`：保存会话、消息、Run、事件、流检查点、Redis、SSE、Ollama 和对话 HTTP 接口。
5. `agent-server`：保存 Spring Boot 启动类、全局 Web 异常边界和部署配置，并装配认证与对话模块。
6. `agent-evals`：保持独立评测模块，不依赖 `agent-server` 实现类。

没有采用仅拆出一个共享模块的方案，因为它仍会让认证和对话在 `agent-server` 中互相渗透；也没有按 API、domain、application、persistence 为每项能力继续细拆，因为阶段 1 尚不需要如此多的构建单元。

## 依赖方向

```text
agent-contracts                  agent-evals
      （独立）                       （独立）

agent-server
  ├── agent-auth ───────────────> agent-platform
  ├── agent-conversation ───────> agent-platform
  └── agent-platform
```

- `agent-platform` 不依赖认证、对话或服务模块。
- `agent-auth` 与 `agent-conversation` 不直接互相依赖。
- 跨功能的当前租户、请求关联和已认证操作者抽象放在 `agent-platform`；认证模块负责建立这些上下文，对话模块只消费抽象。
- `agent-server` 是唯一可执行模块，负责运行时组合，不承载认证或对话业务逻辑。
- `agent-contracts` 只依赖契约测试所需库，不成为任何运行时模块的依赖。
- `agent-evals` 后续只能依赖公开接口或契约，不得导入 `agent-server` 类。

Maven reactor 顺序固定为：`agent-contracts`、`agent-platform`、`agent-auth`、`agent-conversation`、`agent-server`、`agent-evals`。

## 文件归属

### agent-contracts

- `com.datagent.agentv2.contracts.*` 测试类。
- 不包含生产源码；继续从仓库根目录读取 `contracts/` 制品。

### agent-platform

- `platform.id`：前缀 ID 生成。
- `platform.context`：租户和跨模块操作者上下文，不包含 Servlet 过滤器。
- `platform.config`：时钟和 MyBatis-Plus 配置。
- `platform.persistence`：审计实体、审计填充器和 `AuditedServiceImpl`。
- `db/migration`：单一、有序的 Flyway 迁移序列。认证和对话模块拥有实体与服务，但 Schema 变更集中在这里，避免多个 JAR 产生 Flyway 版本冲突。
- PostgreSQL 迁移与 MyBatis-Plus 集成测试。

### agent-auth

- `auth.api`、`auth.application`、`auth.persistence`、`auth.security`。
- 负责把有效登录会话转换成平台定义的已认证操作者和租户上下文。
- 只通过继承 `ServiceImpl`/`AuditedServiceImpl` 的持久化服务访问 Mapper。

### agent-conversation

- `conversation.api`、`conversation.application`、`conversation.domain`、`conversation.persistence`、`conversation.cluster`、`conversation.model`。
- 负责会话 REST、SSE、同节点模型执行、Redis 跨实例转发和 Outbox。
- 只消费平台操作者抽象，不导入认证模块实现。

### agent-server

- `AgentV2Application`。
- `RequestContext`、`RequestContextFilter` 和 `ApiExceptionHandler` 等全局 HTTP 边界。
- `application.yml`、`application-local.yml` 等部署配置。
- 组合认证和对话模块的端到端测试，以及跨模块架构约束测试。

### agent-evals

- 保持当前独立 JAR 骨架，阶段 1 不添加服务端实现依赖。

## 运行时数据流

模块切分不改变已批准的数据流：

1. 浏览器只访问 Nginx。
2. `agent-server` 加载认证与对话模块。
3. 认证模块验证共享数据库中的 Cookie 会话并建立租户/操作者上下文。
4. 对话模块在收到消息的节点事务化保存 Message、Run、事件和 Outbox。
5. 同一节点在事务提交后调用 Ollama。
6. Token 通过 Redis Pub/Sub 转发到持有 SSE 连接的任意实例，生命周期和检查点保存在 PostgreSQL。

模块之间是同一 JVM 内的 Java 调用，不引入网络协议。

## 测试与约束

- 每个模块运行自己的聚焦测试；`mvn verify` 必须验证完整 reactor。
- `agent-contracts` 负责 REST/SSE/清单契约测试。
- `agent-platform` 负责迁移、审计、租户隔离、逻辑删除和乐观锁测试。
- `agent-auth` 负责认证 HTTP 与持久化测试。
- `agent-conversation` 负责对话持久化、REST、Redis、SSE 和 Ollama 测试。
- `agent-server` 负责启动上下文、全局 Problem Detail 和跨模块验收测试。
- 在 `agent-server` 增加架构测试：业务组件不得注入 `BaseMapper`；`agent-auth` 与 `agent-conversation` 不得互相依赖；除 `agent-server` 外不得声明 Spring Boot 启动类。

## 迁移顺序

1. 先创建六个模块 POM 和单向依赖。
2. 将契约测试机械迁移到 `agent-contracts`。
3. 将数据库迁移、MyBatis-Plus 和共享平台代码迁移到 `agent-platform`，改用平台测试启动夹具。
4. 将启动与全局 HTTP 边界留在精简后的 `agent-server`。
5. 创建空的 `agent-auth` 和 `agent-conversation` 骨架，后续阶段 1 任务直接在各自模块中实现。
6. 运行模块聚焦测试和完整 reactor，确认没有同级 V1 路径依赖。

此次重构只移动和约束现有阶段 0/1 代码，不改变外部 REST/SSE 契约、数据库 Schema 或运行行为。
