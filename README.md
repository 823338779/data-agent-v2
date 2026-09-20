# Data Agent V2

Data Agent V2 是面向单实例部署的对话 Agent 工程，包含后端 Maven 多模块、REST/SSE 契约、前端以及可选的上下文压缩策略。

## 目录

- `backend/agent-persistence`：数据库迁移、MyBatis-Plus，以及 ID、时钟和请求上下文基础能力
- `backend/agent-context-lsm`：同步执行的 LSM 会话上下文压缩策略及其摘要持久化
- `backend/agent-context-hierarchical`：同步执行的 Hot/Warm/Cold 分层上下文压缩策略及其摘要持久化
- `backend/agent-data-source`：单一 StarRocks 数据源、语义目录、SQL 安全校验与有界查询
- `backend/agent-workflow`：数据分析固定状态机、瞬时运行快照与服务端执行边界
- `backend/agent-web`：REST/SSE 接口、BFF、会话编排与契约测试
- `backend/agent-server`：唯一可执行模块，负责 Spring Boot 启动、服务级全局拦截器、异常边界与运行时装配
- `backend/agent-evals`：独立评测模块
- `contracts`：当前 REST、SSE 契约与示例
- `docs`：仓库内设计和验证说明
- `examples/dbt-starrocks`：[本地 StarRocks 的 dbt 学习示例](examples/dbt-starrocks/README.md)，涵盖 seed、分层建模、数据测试和增量更新
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

本地开发使用 Vite 同源代理：浏览器只访问前端域名，`/api`（包括 SSE）由 Vite 转发到 V2 后端。默认后端地址是 `http://127.0.0.1:8080`；需要切换实例或 Nginx 时，将 `frontend/.env.example` 复制为 `frontend/.env.local` 并修改 `VITE_BACKEND_ORIGIN`。

先准备 PostgreSQL 数据库 `data_agent_v2` 和 Ollama。本阶段仅支持单个后端进程：

- PostgreSQL 默认连接参数为 `data_agent/data_agent_local_dev`，可通过 `DATA_AGENT_DB_URL`、`DATA_AGENT_DB_USERNAME`、`DATA_AGENT_DB_PASSWORD` 覆盖。
- Ollama 默认使用 `qwen3.5:9b`。先执行 `ollama pull qwen3.5:9b`，再通过 `SPRING_AI_OLLAMA_BASE_URL` 指定服务地址；模型、超时和最大输出分别可由 `DATA_AGENT_MODEL_OLLAMA_MODEL`、`DATA_AGENT_MODEL_OLLAMA_TIMEOUT`、`DATA_AGENT_MODEL_OLLAMA_MAX_OUTPUT_TOKENS` 覆盖。

### 会话侧栏分组

侧栏分为「置顶」「项目」「未分组会话」。项目仅整理会话，不共享文件、知识、指令或上下文；支持创建、改名、折叠、删除，以及项目内新建会话。会话菜单可置顶、取消置顶或移动归属，置顶会话只在置顶区出现，取消后回到原项目。

删除项目会把其中会话移至未分组，保留消息、置顶和正在运行的执行。搜索继续匹配标题和 tag，临时展开命中项目；折叠状态按 API 地址保存在浏览器中。可见页面每分钟刷新侧栏，重新聚焦或切回页面时立即刷新；详情 SSE 的生命周期保持独立。

本次主数据库迁移为 `V5__add_conversation_projects.sql`。前后端配套发布，启动新后端时先由 Flyway 迁移，历史会话默认未分组、不置顶。设计及验证记录见 [会话分组实施计划](docs/superpowers/plans/2026-09-15-session-grouping.md)。

### 会话上下文策略与分层压缩

会话上下文默认使用 `hierarchical` 策略。服务实例只能选择一个策略：`raw` 直接使用原始历史，`lsm` 使用 LSM 摘要树，`hierarchical` 使用单一滚动 Cold 摘要。未知策略或重复注册相同策略 ID 都会使服务启动失败。

| 环境变量 | 默认值 | 允许值或范围 | 说明 |
| --- | --- | --- | --- |
| `DATA_AGENT_CONTEXT_STRATEGY` | `hierarchical` | `raw`、`lsm`、`hierarchical` | 选择服务实例唯一使用的会话上下文策略；取值区分大小写。 |
| `DATA_AGENT_CONTEXT_COMPACTION_FANOUT` | `5` | `3`～`10` | LSM 同层普通节点达到该数量时执行层级合并；仅在 `lsm` 策略下校验和生效。 |
| `DATA_AGENT_CONTEXT_COMPACTION_MAX_LEVEL` | `4` | `1`～`8` | LSM 摘要树的配置顶层，顶层 ROOT 始终最多保留一个活动节点；仅在 `lsm` 策略下校验和生效。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_TRIGGER_RATIO` | `0.75` | `0 < target < trigger < 1` | hierarchical 触发压缩的上下文窗口比例。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_TARGET_RATIO` | `0.70` | `0 < target < trigger < 1` | hierarchical 压缩后争取达到的上下文窗口比例。 |

LSM 默认在输入超过窗口的 75% 时触发并争取压缩至 65%；hierarchical 默认在超过 75% 时触发并争取压缩至 70%。Hierarchical 固定原样保护按 user 消息划分的最后 10 个轮次；更早的连续前缀按需滚入 Cold 摘要。失败或取消留下的空 assistant 不改变轮次边界。压缩在当前请求的本机执行流程中同步完成，不创建后台压缩任务。

需要回滚到原始上下文行为时，应在所有实例中统一设置：

```powershell
$env:DATA_AGENT_CONTEXT_STRATEGY = 'raw'
```

随后重启后端进程。`raw` 模式不会装载压缩插件策略、不会运行插件 Flyway，也不会读取或写入 `conversation_context_lsm_*` 或 `conversation_context_hierarchical_*` 表。切换策略不会删除已有的摘要、checkpoint 或 manifest，可供问题分析或后续重新启用相应插件使用。

选择 `lsm` 时，插件从 `classpath:db/agent-context-lsm/migration` 迁移，并使用独立历史表 `flyway_agent_context_lsm_schema_history`。选择 `hierarchical` 时，插件从 `classpath:db/agent-context-hierarchical/migration` 迁移，并使用独立历史表 `flyway_agent_context_hierarchical_schema_history`。主 Flyway 始终先完成通用迁移；插件迁移分别只管理 `conversation_context_lsm_*` 或 `conversation_context_hierarchical_*` 对象，不污染主 Flyway 历史。

Hierarchical 上下文验证命令：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=*Hierarchical*Test' test
```

LSM 上下文验证命令：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=*Lsm*Test' test
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

该模式不读取或保存 OpenAI API key。每次模型调用使用临时、只读、禁止审批的 Codex thread；Codex 仅能选择 V2 注册的领域工具，真正的工具执行和持久化仍由 V2 ReAct 执行器完成。可选配置包括 `DATA_AGENT_MODEL_CODEX_EXECUTABLE`、`DATA_AGENT_MODEL_CODEX_MODEL`、`DATA_AGENT_MODEL_CODEX_EFFORT`、`DATA_AGENT_MODEL_CODEX_TIMEOUT` 和 `DATA_AGENT_MODEL_CODEX_WORKING_DIRECTORY`。部署到服务器或容器时，必须在运行后端的同一系统用户和环境中单独完成 Codex 登录；个人 ChatGPT 登录态不应复制到共享服务。

### 内置 ReAct 验证工具

Tool 和 Skill 分别由独立 Maven Module `agent-tool`、`agent-skill` 承载。服务启动后，`system_timezone`、`current_time`、`skill_read` 默认可见；`web_search`、`web_fetch` 默认隐藏。模型需要先读取 bundled `web-research` Skill，系统才会在本次 execution 内原子激活两个联网 Tool，下一轮 ReAct 才能看到它们。

联网搜索首版固定使用 Tavily，需通过 `DATA_AGENT_TAVILY_API_KEY` 配置服务端密钥；未配置时应用仍可启动，实际搜索返回 `web_search_not_configured`。`web_fetch` 用于继续读取搜索结果 URL 的 HTML 或纯文本正文，限制为公开 HTTP(S) 地址，并对每次重定向重新执行内网/本机地址检查、响应类型检查和响应体大小限制。搜索词和目标 URL 都属于外部数据，Skill 会要求模型不要提交凭据、客户机密或未脱敏个人信息，也不能把页面正文当作指令。搜索配置可使用 `DATA_AGENT_WEB_SEARCH_*` 环境变量；抓取超时、重定向数、响应体大小、正文字符数和 User-Agent 可使用对应的 `DATA_AGENT_WEB_FETCH_*` 环境变量覆盖。自动化测试不访问真实第三方服务。

### 数据分析 Workflow

数据分析请求与原有 ReAct 执行器是同级路由。服务只在启动时配置一个 StarRocks 数据源；数据请求进入固定的强类型 Java 状态机：语义资源检索、`QueryPlan` 构建与校验、逐任务生成 SQL、SQL 本地校验、`EXPLAIN`、执行查询、结果校验、结果分析和最终回答。当前实现不包含 MQL，也不使用 DSL 动态定义或改写流程。

Workflow 的步骤、重试次数、查询任务数量、SQL/结果大小和总执行时间都由服务端配置限制，模型不能改变这些边界。多个查询任务按顺序执行。只有 SQL 通过本地 AST 白名单校验和 StarRocks `EXPLAIN` 后，才会通过 `workflow_update` SSE 展示完整 SQL；查询结果行不会进入 SSE、日志或持久化。

中间状态只保存在当前 JVM 内存中，用于实时界面和同一次连接内的快照握手。服务重启或执行结束后不恢复、不续跑，也不会把 Workflow 步骤写入数据库。会话消息和最终回答仍沿用现有持久化路径。

启用前必须先完成两项配置：

1. 在 `backend/agent-server/src/main/resources/semantic/starrocks.yaml` 中按实际 Schema 配置至少一个 dataset，并配置需要使用的指标和关联关系。
2. 提供唯一 StarRocks 实例的连接参数后设置 `$env:DATA_AGENT_WORKFLOW_ENABLED = 'true'`。

最小连接配置如下；密码只能从运行环境注入，不要写入仓库：

```powershell
$env:DATA_AGENT_STARROCKS_JDBC_URL = 'jdbc:mysql://starrocks-host:9030/analytics'
$env:DATA_AGENT_STARROCKS_USERNAME = 'data_agent'
$env:DATA_AGENT_STARROCKS_PASSWORD = '<secret>'
$env:DATA_AGENT_STARROCKS_CATALOG = 'default_catalog'
$env:DATA_AGENT_STARROCKS_DATABASE = 'analytics'
$env:DATA_AGENT_WORKFLOW_ENABLED = 'true'
```

`DATA_AGENT_WORKFLOW_MAX_QUERY_TASKS` 等阈值可在服务端随时调整，完整默认值见 `backend/agent-server/src/main/resources/application-business.yml`。未启用时不创建 StarRocks 连接池，所有请求继续走原有 ReAct 执行器；启用后，连接配置、语义目录或物理 Schema 不一致会使服务启动失败。

单实例联调分别启动：

```powershell
# 终端 1：V2 后端
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run

# 终端 2：V2 前端
cd .\frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173` 即可进入工作区。当前阶段后端固定使用 `tenant_local` 租户和 `user_admin` / `admin` 身份，不提供登录、退出或租户切换；这些能力将在后续重新设计。当前可联调按更新时间排序的多会话、本机执行取消/重试和模型回答流。服务启动时会把上次进程退出后仍为 `running` 的执行直接标记为失败，不做跨进程恢复或接管。

## 服务端黑盒评测

`agent-evals` 不依赖产品运行时模块，只通过公开 HTTP 契约对当前已启动的服务配置执行语料、
评分并输出独立 JSON 评测结果。模块不负责读取或比较其他配置的结果；详细运行方式见
[`backend/agent-evals/README.md`](backend/agent-evals/README.md)。

前端生产构建：

```powershell
cd .\frontend
npm.cmd run build
```

## Git 仓库维护

Git 能力默认关闭。启用后可通过会话工具准备草稿与冻结候选，并在 Git 卡片查看完整文件差异、确认或取消；已确认失败可按状态重试或核实。“已推送”表示远端 `main` 已接受提交。

- [Agent Git 运行配置与接口](docs/agent-git-runtime.md)
- [独立 Git 仓库接入与运维](docs/git-repository-setup.md)
