# Data Agent V2

Data Agent V2 是面向单实例部署的对话 Agent 工程，包含后端 Maven 多模块、REST/SSE 契约、前端以及可选的上下文压缩策略。

## 目录

- `backend/agent-persistence`：数据库迁移、MyBatis-Plus，以及 ID、时钟和请求上下文基础能力
- `backend/agent-context-lsm`：同步执行的 LSM 会话上下文压缩策略
- `backend/agent-context-hierarchical`：同步执行的单一滚动 Cold 会话上下文压缩策略
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
| `DATA_AGENT_CONTEXT_HIERARCHICAL_TRIGGER_RATIO` | `0.75` | `0 < target < trigger < 1` | 旧 V2 参数，保留兼容；V3 不使用该比例。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_TARGET_RATIO` | `0.70` | `0 < target < trigger < 1` | 旧 V2 参数，保留兼容；V3 不使用该比例。 |

| `DATA_AGENT_CONTEXT_HIERARCHICAL_STEP_TRIGGER_RATIO` | `0.70` | `0 < target < trigger < 1` | V3 相对于可用输入预算 H 的触发比例。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_STEP_TARGET_RATIO` | `0.55` | 同上 | V3 压缩后的目标比例。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_STEP_RETAINED_GROUPS` | `2` | 非负整数 | 最近完整步骤组的软保留数，预算不足可减到 0。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_STEP_PAYLOAD_MAX_BYTES` | `1048576` | 正整数 | 一份脱敏工具原文的保存上限，单位字节。 |
| `DATA_AGENT_CONTEXT_HIERARCHICAL_STEP_RESULT_PAGE_MAX_BYTES` | `4096` | 正整数 | 安全原文读取页上限，单位字节。 |

V3 的 `H = contextWindowTokens - maxOutputTokens - safetyReserveTokens`。每次业务模型调用前估算完整输入，在 `0.70H` 触发同步压缩，争取降到 `0.55H`；最近两组只作软保留。单个用户请求中的完整 ReAct 步骤也能进入同一份滚动 Cold，当前用户原文只保留一次，未完成的工具组不得拆分或压缩。LSM 仍采用原有 75%/65% 窗口策略，旧 V2 的固定十轮保护仅用于历史协议兼容。

工具输入和结果经现有 sanitizer 后归档；模型只接收有界视图，必要时通过 `context_result_read` 在当前执行所属会话内分页读取。单载荷视图及包含元数据的读取页受 `min(4096, floor(0.10H))` 预算约束；超过存储上限或缺失的历史原文明确标记不可恢复。载荷随会话删除，读取不接受路径、URL 或模型指定的会话身份。

摘要生成自身的提示、schema、旧摘要和新来源同样受预算约束，每批最多两次格式尝试，且必须净缩减。相同失败输入不会反复生成；仍低于 H 时沿用已有视图，超过 H 则在下一次业务模型调用前失败。压缩不重置 ReAct 累计次数或 deadline，不重放已执行工具，也不代替 push 的显式确认。

V4/V5 插件迁移增量添加 V3 游标和安全载荷表，不删除 V2 摘要。仅来源可验证的旧覆盖范围可转换，否则从保留的真实历史重建；缺失材料时明确失败。部署必须统一升级所有写入实例，禁止 V2/V3 混写。回退旧二进制需要恢复匹配的数据快照；仅切换 `raw` 不需要删除或倒退迁移。

需要回滚到原始上下文行为时，应在所有实例中统一设置：

```powershell
$env:DATA_AGENT_CONTEXT_STRATEGY = 'raw'
```

随后重启后端进程。`raw` 模式不会装载压缩插件策略、不会运行插件 Flyway，也不会读取或写入 `conversation_context_lsm_*` 、`conversation_context_hierarchical_*` 或 `conversation_context_payload` 表。切换策略不会删除已有的摘要、checkpoint 或 manifest，可供问题分析或后续重新启用相应插件使用。

上下文 Entity、Mapper、存储服务和迁移资源统一位于 `agent-persistence`，策略模块只负责规划、生成和运行编排。选择 `lsm` 时，持久化模块从 `classpath:db/agent-context-lsm/migration` 迁移，并使用独立历史表 `flyway_agent_context_lsm_schema_history`。选择 `hierarchical` 时，插件从 `classpath:db/agent-context-hierarchical/migration` 迁移，并使用独立历史表 `flyway_agent_context_hierarchical_schema_history`。主 Flyway 始终先完成通用迁移；条件迁移分别只管理 `conversation_context_lsm_*` 或 Hierarchical 的 `conversation_context_hierarchical_*`、`conversation_context_payload` 对象，不污染主 Flyway 历史。

Hierarchical 上下文验证命令：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=*Hierarchical*Test' test
```

单请求压缩验收使用真实 HTTP、ReAct、工具和 PostgreSQL，只替换模型端口。覆盖一条请求四十个工具步骤、至少两次发布及复用摘要、连续三轮同会话，以及压缩后累计限制仍生效：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dtest=HierarchicalLongReactIntegrationTest' test
```

替身验收证明流程与协议，不代表真实模型摘要质量；真实模型需另用脱敏业务样本检查目标、约束、身份、错误及待办保留。

已登录本机 Codex 后可显式执行真实摘要质量探针（默认模型与应用一致，可通过 `DATA_AGENT_MODEL_CODEX_MODEL` 覆盖），输出脱敏结果到 `backend/agent-server/target/real-summary-quality.json`：

```powershell
$env:RUN_REAL_CONTEXT_QUALITY = 'true'
try {
  .\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
    '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=HierarchicalRealSummaryQualityTest' test
} finally {
  Remove-Item Env:RUN_REAL_CONTEXT_QUALITY
}
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

## 开发会话与 dbt-service

`agent-development` 编排保存、预览、交付与恢复；`agent-git` 提供受控完整工程文件和 Git 原语；`agent-dbt` 只封装 dbt-service `/v3` 引擎契约。持久事实统一位于 `agent-persistence`，HTTP 和模型工具调用同一组用例。三个产品各自构建，通过 HTTP 协作。

开启 Git 后，在创建会话时选择 Agent 项目和真实 Git 分支。项目通过 `/api/v1/agent-projects/{projectId}/development-config` 配置 repository、previewBinding/previewConfigVersion/previewSchemaName、productionBinding/productionConfigVersion/productionSchemaName。两个 schema 都必须由用户显式填写，满足 `[a-z][a-z0-9_]{0,255}`，构建按环境取值并冻结到操作快照；旧配置仍可读取，补全后才能发起新的预览或交付。旧操作不会套用新 schema，需要另建操作。项目一旦存在开发会话便不能更换仓库。会话目标不可更换，普通会话保持独立。编辑支持受控目录内完整 dbt 工程，包括宏、seed、项目配置与原始 YAML 字节。

- 保存仅提交并核实 Git push，不做 YAML 或 dbt 预校验。未推送的本地文件不会写入数据库；Pod 丢失本地文件时明确报告 `LOCAL_INPUT_LOST`。
- 开发预览先保存，再以实际 pushed SHA 创建 PREVIEW 构建，等待该构建自身的初始部署意图。
- 交付要求本地已保存，并确认整个共享分支相对 main 的差异、两端 SHA 及配置摘要；合并后以实际 merge SHA 创建独立 PRODUCTION 构建。预览不能直接提升为正式版本。
- 动作返回持久 operationId。后台独立恢复，同幂等键必须保持相同输入；未知结果重放原候选和请求键。明确失败的构建可通过 expectedVersion 和恢复幂等键增加一次尝试，仍使用原 SHA。
- 状态分别展示本地修订、远端来源、构建与部署；构建成功并不等于部署成功。构建页面和历史查询固定 buildId。

```powershell
$env:DATA_AGENT_GIT_ENABLED='true'
$env:DATA_AGENT_DBT_ENABLED='true'
$env:DATA_AGENT_DBT_BASE_URL='http://127.0.0.1:8000'
# DATA_AGENT_DBT_SERVICE_TOKEN 由本地安全环境注入，与目标服务的 SERVICE_TOKEN 匹配。
```

仅开启 dbt 时，普通分析会话可由服务端 `DATA_AGENT_DBT_ANALYSIS_BUILD_ID` 固定分析构建。开发会话首次从自身 PREVIEW 部署解析并固定 buildId，未部署时明确失败，不回退 main。模型不能覆盖仓库、分支或可信会话身份。可用维度通过 `/v3/query/available-dimensions?build_id=…` 获取；QUERY、EXPLAIN、DIMENSION_VALUES、PREVIEW 经 `/v3/query?build_id=…` 同步返回本次结果，不保存历史查询结果，也不支持结果分页。查询不携带 idempotencyKey。构建与发布仍为异步，不由数据分析工具编排。

`DATA_AGENT_DBT_REQUEST_TIMEOUT` 默认 `1860s`，覆盖 dbt-service 默认 `1800s` 命令期限及响应传输；分析工具在该 HTTP 期限上另加 15 秒。`DATA_AGENT_EXECUTION_DEADLINE` 默认 `35m`，为同步查询与模型前后处理保留总预算。调整引擎命令期限时应同时调整 HTTP 与 execution 预算；自定义 execution 总期限仍是整个会话执行的上限。

HTTP 开发入口为 `/api/v1/agent-sessions/{sessionId}/development` 及 save、preview、deliver、operations 子路径；项目 branches 入口枚举及显式创建真实 Git 分支。`/api/v1/development/contract` 暴露与打包 OpenAPI 同源的运维契约。旧工作区写接口、validation 和旧 dbt 路由已退出。旧 Git 卡片只供历史审计。

Agent 与指标平台不直接交互。前端仅展示操作对应的固定 buildId，不生成指标平台链接。查询保留服务端时间解释和高精度结果；resultTruncated 表示引擎限制了结果，不能宣称结果完整。工具结果超过 20000 字符时返回 `DBT_OUTPUT_TOO_LARGE`，应缩小 limit、字段或筛选范围后重新查询。

升级必须先停旧写入并备份，再部署 `/v3` 服务、执行 V15 开发上下文迁移、启动配套 Agent 和平台。旧多仓库项目必须显式给出 repositoryId 到仓库地址的映射；有歧义时禁用新开发操作并逐项报告，旧历史保留。不得让旧发布器和新动作同时写入；恢复使用匹配备份或前向修复。

接入验证使用临时真实 Git 仓库和 Testcontainers PostgreSQL，需要 Docker：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-development -am '-Dtest=Development*Test,SourceSaveIntegrationTest,PreviewBuildIntegrationTest,DeliveryIntegrationTest,GitDevelopmentRepositoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=Dbt*Test,Development*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

更多接口和恢复限制见 [开发运行说明](docs/agent-git-runtime.md)。

历史开发上下文迁移使用一次性入口。先停止旧写入与发布任务，备份数据库和本地工程；先以新版本完成 schema 升级并显式配置项目，再停服务执行以下命令。输入 JSON 仅包含 `projectId` 和 `repositoryMappings`（旧 repositoryId 到明确仓库地址的映射），不包含凭据。默认 dry-run 会执行相同转换并回滚；确认报告无来源冲突后，再将最后一个参数改为 `false`。命令完成后进程退出，重复执行保留已经迁移的会话标识与来源 SHA。未推送文件不参与迁移，也不会被删除。

```powershell
java -jar .\backend\agent-server\target\agent-server-0.1.0-SNAPSHOT.jar --spring.main.web-application-type=none --data-agent.git.enabled=false --data-agent.dbt.enabled=false --data-agent.development.migration.enabled=true --data-agent.development.migration.input=C:/secure-local/development-migration.json --data-agent.development.migration.dry-run=true
```
