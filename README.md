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

## Git 仓库维护

Git 能力默认关闭。启用后在“开发工作区”选择或创建分支草稿，通过会话工具编辑与冻结候选，在 Git 卡片审阅完整差异并确认；失败后可按状态重试或核实。“已推送”表示候选固定分支已接受提交，预览构建状态单独展示。会话和工作区是多对多关联，切换选择只影响下一次执行。

- [Agent Git 运行配置与接口](docs/agent-git-runtime.md)
- [独立 Git 仓库接入与运维](docs/git-repository-setup.md)

## dbt-service 接入

agent 通过独立 `agent-dbt` 模块直接访问 dbt-service 的 `/v2/projects`，不经过 Metric Platform。默认关闭；仅开启 dbt 即可查询已发布数据，不需要 Git。

```powershell
$env:DATA_AGENT_DBT_ENABLED='true'
$env:DATA_AGENT_DBT_BASE_URL='http://127.0.0.1:8000'
$env:DATA_AGENT_DBT_PROJECT_ID='sample'
$env:DATA_AGENT_DBT_PROJECT_DIRECTORY='.'
```

地址和项目只能由服务配置，不能由模型覆盖。子目录必须与 dbt-service 的 `projectSubdir` 一致。建连默认 5 秒、请求默认 20 秒（最大 20 秒，覆盖正文读取）；应用启动不访问远端，首次使用核对 `agent-dbt-v1` 能力声明。数据分析 Skill 提供目录、资源、异步查询选项、查询提交和结果分页。

多步建模和查询默认允许单次执行最多 32 次模型调用、32 次工具调用，执行时限仍为 10 分钟；可用 `DATA_AGENT_REACT_MAX_MODEL_INVOCATIONS`、`DATA_AGENT_REACT_MAX_TOOL_CALLS` 调整。异步校验和查询的轮询也计入调用次数。

非 Ollama provider 的上下文预算可用 `DATA_AGENT_CONTEXT_WINDOW_TOKENS` 设置，默认 65536。当前估算按 UTF-8 字节保守计量，并原样保护近期 10 个用户轮次；连续建模、查询和复查可能触及硬上限。应先核实实际模型容量再调整本地预算，例如已确认模型支持 272000 时可保守设为 131072；不要超过所用模型容量或关闭硬上限保护。

维护分支时还需开启 Git，并指向与服务绑定一致的仓库。配置 `DATA_AGENT_DBT_BRANCH_SERVICE_TOKEN` 与服务端 `BRANCH_SERVICE_TOKEN` 一致。服务必须支持 `branch-development-v1` 和 `draft-validation-v2`，分支 publication 必须返回当前 configVersion/toolchainVersion。流程为：选择独立工作区 → 冻结修订 → dbt 定义验证 → 确认卡片 → 用户点击确认 → 固定分支 push。支持 `models/`、`tests/` 下的 SQL/YAML，禁止宏和项目配置变更；实际 SQL 支持范围由服务验证。

定义验证包含 YAML/重复键、模板、dbt parse/compile、SQL 定义和语义引用检查；`SUCCEEDED` 且 `valid=true` 才可准备候选，不证明物理字段或构建成功。编辑、同步及服务配置变化使旧证据失效。共享分支远端推进时需先同步、重新验证及确认。查询固定分支的已发布版本，预览未就绪会明确失败。仅开启 dbt 时保留原线上分析；同时开启 Git 时分析也需要选择开发工作区。

前端构建时可设置 `VITE_METRIC_PLATFORM_URL` 为指标平台地址。推送卡片按原提交 SHA 查找预览，并链接到固定 branchId/releaseId；未配置时仍显示预览状态。工作区使用方式、迁移和恢复限制见 [运行说明](docs/agent-git-runtime.md)。

升级先部署支持本协议的 dbt-service，再部署 agent（Flyway 自动增加 V11/V12），最后启用开关。超时保留原受理键或任务 ID 重试；发布过期重新取目录及选项；校验 FAILED 可对同一草稿重新发起尝试，valid=false 则先修正定义。

时间使用带 offset 的 ISO 输入，服务按发布固定业务时区解释；历史无 offset 平台调用保持原行为。查询结果最多每页 200 行，agent 按完整行裁剪到 20000 字符。始终用实际 `nextOffset` 继续；`availableRows` 仅表示已保存行数，`resultTruncated=true` 时不能宣称结果完整。

当前时间边界遵循 MetricFlow 的包含结束时间及粒度对齐规则，不是左闭右开区间。整月查询应以月末最后一秒为 `endTime`，不能传下月首日；`normalizedTimeRange` 是时区转换后的输入，不能当作引擎实际对齐边界。

接入验证（临时 Git 裸仓库及 Testcontainers PostgreSQL，需要 Docker）：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am '-Dtest=Dbt*Test,GitLifecycleTest,GitChangeControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
