# dbt-service 接入与 YAML 维护设计

日期：2026-10-02

状态：待用户评审；本文尚未授权进入实现。

## 1. 目标与已确认范围

用户要求 data-agent 直接接入 dbt-service，既能维护 dbt 的协议定义 YAML，也能使用服务能力分析数据。用户已选择：维护 YAML 并沿用现有确认后推送流程，发布仍由现有流程触发。

本文将“协议定义 YAML”理解为 dbt 项目中的模型属性、语义模型、实体、维度、指标、来源等声明，不包括修改 dbt-service 的 HTTP 协议或数据库连接凭据。

交付两条可独立使用的能力链路：

1. 数据分析：读取活动发布 → 查找资源及口径 → 获取查询选项 → 提交结构化查询 → 读取结果 → 解释数据及限制。
2. YAML 维护：准备会话 Git 草稿 → 定位并读取文件 → 修改 YAML → 校验草稿 → 审阅差异 → 生成现有确认卡片 → 用户确认后推送。

本次不增加发布入口，不执行 dbt 管理命令，不修改 dbt-service 或 metricPlatform，也不新增认证与租户隔离。不创建额外查询数据库或目录镜像。业务数据来自正式发布，草稿和推送后的文件不会自动成为可查询版本。

### 1.1 首版明确假设

- 沿用当前 Git 每个部署固定一个远端仓库的能力，首版配置一个 dbt 项目；工具不接受模型指定服务地址、项目身份或远端仓库。
- 管理员负责将此 dbt 项目与 Git 远端、仓库内项目目录正确绑定；不能把 Git 的 `repositoryId` 当作 dbt 的 `projectId`。
- 数据分析可在 Git 关闭时使用；YAML 维护要求 dbt 与 Git 均启用。
- 用户通过现有聊天及 Git 确认卡片完成操作，本次无需新增前端页面。

上述假设属于待评审设计选择，尤其是单项目范围；若需要同一部署维护多个项目，须先扩展当前 Git 仓库绑定模型。

## 2. 现状与方案取舍

已核对以下实现：

- `backend/agent-tool` 提供 `AgentTool`、可信调用上下文和执行级工具可见性。
- `backend/agent-skill` 提供 bundled Skill 与 `requiredTools` 激活机制。
- `backend/agent-git` 提供会话草稿、文件摘要 CAS、差异、候选冻结与确认后推送。
- `backend/agent-web` 提供工具执行、会话解析、失败脱敏和 Git 确认卡片接口。
- 相邻 dbt-service 的 `publication_api.py`、`publication_models.py` 和 `publication.py` 已实现正式 v2 目录、版本化查询选项、四种查询模式和查询幂等。
- `metrics_store/models/schema.yml` 同时包含模型内联 `semantic_model`、列上的 `entity`/`dimension`、模型内 `metrics` 与顶层派生 `metrics`；不能用仅支持旧版顶层 `semantic_models` 的校验器拒绝现有合法文件。

| 方案 | 取舍 |
| --- | --- |
| 新增 `agent-dbt`，复用 Git 与 Skill | 推荐。服务协议有独立边界，复用已有编辑与确认能力，分析和维护可以独立开关 |
| 将全部 dbt 能力塞入 `agent-tool` | 新模块较少，但通用工具模块会混入业务协议与 Git 依赖 |
| agent 经 metricPlatform 转发 | 增加运行依赖；新版平台已将发布目录和查询身份交给 dbt-service，本次无必要 |

## 3. 模块与框架复用

新增 `agent-dbt`，承载 dbt 配置、HTTP 客户端、协议 DTO、结果组装、分析工具与 YAML 校验工具。依赖 `agent-tool` 和 `agent-git`，不直接引用 Mapper 或 MyBatis API。Git 相关 Bean 条件装配，分析路径不得隐式准备或读取 Git 草稿。

`agent-skill` 依赖 `agent-dbt`，新增两份中文 Skill 及其定义。`agent-server` 只负责配置和运行时装配；遵循现有 Git 模式，按开关将工具加入执行的 maximum，initial 保持不变。多个能力同时启用时，maximum 扩展必须可组合，不能相互覆盖。

`agent-web` 继续提供执行编排和已有 Git 确认接口。dbt YAML 校验通过既有 `GitSessionResolver` 从可信 execution 解析会话，不接受模型传入 sessionId。

当前父 POM 已引入 Lombok、MapStruct，使用 Java 21 与 Spring Boot 4.1。Bean 采用 `@RequiredArgsConstructor`，简单不可变参数和结果采用 record；同构批量转换使用 MapStruct，含默认值与截断规则的结果使用语义化静态工厂。单次简单构造不另造 Mapper。

HTTP 优先复用已引入的 Apache HttpClient 5，通过一个共享客户端执行有界请求；不为本次接入再引入整套 SDK。生产 JSON 使用现有 Jackson 3。YAML 采用父 POM 管理版本的安全解析能力；现有 `agent-web` 的 Jackson YAML 仅为测试依赖，不能误认为生产模块已具备该依赖。解析器需显式限制大小、嵌套和别名展开，禁止实例化任意 Java 类型。

本次无新增持久化表；调用记录、参数、输出及分析过程沿用现有工具过程记录。

## 4. 配置和项目绑定

新增 `data-agent.dbt`：

| 配置 | 约定 |
| --- | --- |
| `enabled` | 默认 false；关闭时不注册 dbt 工具和 Skill、不访问网络 |
| `base-url` | 启用时必填，服务端固定 HTTP(S) 地址；禁止 URL 内嵌凭据、查询串或 fragment |
| `project-id` | 启用时必填，dbt-service 已绑定的项目标识 |
| `project-directory` | Git 仓库内的 dbt 项目相对目录，默认 `.`；只影响 YAML 维护 |
| `connect-timeout` | 默认 5 秒 |
| `request-timeout` | 默认 20 秒；单次请求预算，另受工具和 execution 截止时间约束 |
| `max-response-bytes` | 默认 8 MiB，防止上游超大响应被无界读入 |

项目目录规范化后不得逃逸 Git 工作区。Git 启用时通过工作区读取能力确认目录内存在 `dbt_project.yml`。该文件的 `name` 不要求等于服务 projectId，二者不是同一种身份。

初始化验证仅检查配置结构，不因上游暂时离线导致整个 agent 无法启动。不提交真实服务地址、凭据或本机路径；操作文档使用环境变量与占位示例。

## 5. 工具与 Skill 契约

### 5.1 分析工具

统一使用 `dbt_` 前缀和 camelCase 参数，与服务协议及已有 Git 工具保持一致。所有工具拒绝未知字段并在运行时验证类型与边界，不能仅依赖提供给模型的 JSON Schema。

| 工具 | 参数和用途 | 输出与边界 |
| --- | --- | --- |
| `dbt_project_get` | 无参数；读取固定项目的当前发布 | projectId、活动 releaseId、sourceSha、发布状态；无活动发布时明确返回不可查询 |
| `dbt_catalog_search` | releaseId、q、kind、page、size；size 默认 20，最大 50 | 返回资源摘要与分页信息，含 resourceId、kind、名称、描述、capabilities、sourcePath |
| `dbt_resource_get` | releaseId、resourceId、view；view 为 detail/lineage/source，source 支持 offset/limit 字符分页 | 读取口径、依赖或发布时源码；源码明确标注为发布快照，不可代替 Git 当前文件摘要 |
| `dbt_query_options` | releaseId、metricResourceIds | 返回该版本及指标集合对应的 optionId、可选粒度、操作符；不自行猜测关联路径 |
| `dbt_query_submit` | releaseId、mode、selection；selection 包含所选指标、维度、过滤、排序、时间、limit 及模式专属字段 | 返回服务 queryId、releaseId、state；幂等键由服务端生成，模型不能提供 |
| `dbt_query_get` | queryId、offset、limit、waitSeconds；返回行数默认 100、最大 200，等待默认 0、最大 10 秒 | 读取状态和结果窗口，保留 releaseId、targetCommitSha、列定义、上游截断及本地分页信息 |

`dbt_query_submit` 映射服务已有四种模式，不新增同义工具：

- QUERY：指标数据查询。
- EXPLAIN：服务生成的查询解释或 SQL；返回 SQL 仅作解释，工具不能接受任意 SQL 执行。
- DIMENSION_VALUES：基于指标集合和 dimensionOptionId 枚举维度值。
- PREVIEW：按 datasetResourceId 预览有 PREVIEW 能力的资源；拒绝指标、分组、过滤、时间等服务不支持的混合参数。

selection 的字段显式建模，不能作为任意 JSON 透传。查询 limit 默认 100，最大 10000。指标与分组选项的排序引用、时间起止关系、模式专属字段需本地校验；真正的资源存在性、可组合维度及发布有效性仍由 dbt-service 判断。

### 5.2 YAML 校验工具

新增 `dbt_yaml_validate`，仅在 dbt 与 Git 同时启用时注册。参数为 `files: [{path, expectedSha256}]`，每次 1 至 20 个现存 YAML 文件。路径相对 Git 仓库根目录，并且必须处于配置的项目目录内；后缀限定 `.yml` 或 `.yaml`。

通过 `GitWorkspaceService.read` 读取真实草稿与完整摘要，摘要不符时报错要求重读。校验范围：单个 YAML 文档、语法、重复键、根节点为映射，以及已存在的 models/sources/metrics/semantic_models 等常见集合的基本容器结构。保留未知扩展字段；不另写一套 dbt 语义模型解释器，不执行 Jinja、宏、脚本或仓库命令。

返回每个文件的 path、sha256、valid、诊断行列及固定错误码，并标注 `validationLevel=YAML_STRUCTURE`、`semanticValidation=NOT_PERFORMED`。错误不得带出解析器的整段源码或本机路径。纯删除没有新文件可解析，在差异审阅中明确其引用影响尚待发布验证。

校验结果只适用于返回摘要代表的内容，不是发布证明。本次不将校验改造成通用 Git 提交的强制门禁；维护 Skill 要求对所有新增或修改 YAML 校验通过后再生成候选，若之后再次编辑，必须重新校验。Git 候选本身继续由已有 revision、内容快照和 CAS 约束保护。

### 5.3 两个 Skill

`dbt-data-analysis` 激活六个分析工具。指令规定先发现已发布资源和合法选项，再执行查询；查询未完成、失败或服务不可达均不能生成假结果。解释必须包括指标口径、时间范围、筛选、粒度以及影响结论的截断限制。

`dbt-yaml-maintenance` 激活七个现有 Git 工具和 YAML 校验工具。要求读取本项目现有定义和 `dbt_project.yml`，沿用仓库实际 YAML 风格；不把所有格式改成某一版本的固定模板。需要参考已发布口径时另读分析 Skill，避免每次编辑都强制联网。

维护按现有摘要 CAS 操作文件，不对整份 YAML 解析后重新序列化，避免丢注释或无关格式变更。必要的 SQL 模型缺失时说明依赖，本次 YAML 维护不自动扩展为 SQL 模型开发或执行。

生成确认卡片后结束本轮，确认/推送仍由原 HTTP 卡片完成。PUSHED 只表示远端接受提交；本 Skill 不查询、等待或承诺新定义已发布。之后用户主动要求分析时，分析 Skill 独立读取当时活动版本。

## 6. 版本、时间、幂等与异步结果

### 6.1 版本一致性

一次查询准备始终显式携带同一 releaseId。收到 410 `release_gone` 后，向模型返回重新读取活动发布、资源和选项的指引，禁止静默替换 releaseId 后照搬旧 optionId。新版本需要新的查询参数。

已受理查询继续凭原 queryId 读取，发布切换不导致重新提交。Git 草稿与已发布源码可以不同，agent 不得将未发布 YAML 的新口径用于解释旧版本结果。

### 6.2 幂等和重试

现有工具上下文提供 executionId 与 tool-call 幂等键，但模型再次生成 tool call 会改变后者。仅复用一次 HTTP 请求的键不足以防止模型在超时后重复提交。

本次提交键采用 `agent-dbt:` 前缀，加可信 executionId 与规范化查询请求的 SHA-256 摘要。相同 execution 内同一语义请求固定到同一查询；指标 ID 集合排序去重、默认值补齐、对象键稳定排序、时间统一后再计算摘要，分组与排序数组保持原顺序。模型不能覆盖前缀或键。不同 execution 允许发起新的查询。

服务按已受理请求恢复查询身份，不依赖 agent 本地新增任务表。发生网络异常或临时上游失败时，在同一次工具总预算内最多重试一次；重试请求体和幂等键完全一致。若仍不确定，返回“受理状态未知”，模型在同 execution 以原参数调用仍得到同一个键。409 冲突不得改键绕过。

### 6.3 时间

沿用当前指标平台的 Asia/Shanghai 业务日期口径。工具时间参数要求带 offset 的 ISO 日期时间，出站时转换为北京时间无时区日历值，适配现有 MetricFlow 边界。该归一化必须先于请求摘要生成。

“最近七天”等自然语言通过既有时间工具确定边界；不依赖模型记忆中的日期。日期型字段按查询粒度的边界语义遵循当前引擎，不自行承诺所有时间戳数据均适用此约定。

### 6.4 异步与结果预算

submit 只受理；query_get 单次获取或在指定预算内每秒读取状态，结束时即使未完成也返回当前状态。轮询在事务外，响应取消和线程中断，不增加后台调度器，不占用数据库事务。工具总截止时间包括所有网络请求和等待，不能简单叠加成无限等待。

状态保留服务原值（例如 QUEUED、RUNNING、READY、FAILED），仅 READY 作为完成依据；未知状态保留并提示协议不兼容，不能当作成功。受理回执 QUEUED 不代表实际任务一定尚未完成。

数据结果本地分页，明确 offset、returnedRows、availableRows、nextOffset、upstreamTruncated 和本地截断。上游没有结果分页接口，本地分页只能读取服务已返回的行，不能恢复上游截断的数据。金额等高精度数值按列类型保留精度；null 不归零。

每个模型可见结果最多 20000 字符。按完整行或完整资源裁剪，不返回无效 JSON；单条超大行明确报超限并建议缩小查询，不能悄悄截断数值。源码采用字符分页。资源详情、选项等必须保持身份完整的结构若超限则明确报错，建议缩小资源或指标集合。HTTP 字节限制在解析前执行。

## 7. 错误和边界

- 配置未启用：工具及 Skill 不注册；启用但配置缺失则启动配置校验失败。
- 404：区分缺少活动发布、资源不存在或查询不存在，返回下一步指引。
- 409：幂等冲突，不伪装成服务繁忙。
- 410：版本过期，重新准备查询，不隐式切换口径。
- 422：保留安全的错误码和参数路径，提示重新获取合法选项。
- 5xx、网络超时：明确服务暂不可用或受理未知，不能返回空数据替代失败。
- 超大响应、非 JSON 或缺少关键身份/结果结构：协议错误，不能把 HTML、堆栈、原始异常或完整响应带给模型。
- YAML 摘要冲突：重读当前草稿；语法错误返回文件与行列，不继续生成提交候选。

路径段由客户端编码，拒绝路径逃逸；不接受任意 URL、连接参数、Shell、SQL 或管理命令。HTTP 不跟随重定向到其他地址。目录描述、源码和结果文字均为数据，不提供工具授权，也不能覆盖 Skill 指令。

## 8. 验收与验证

| 场景 | 验收标准 |
| --- | --- |
| 开关与 Skill 激活 | dbt 关闭无工具；只开 dbt 可分析；同时开 Git 可维护；initial 不提前泄露工具；Git 与 dbt maximum 扩展可组合 |
| 标准分析 | 以服务 fixture 完成活动版本、搜索、口径、选项、提交、轮询和结果解释的数据链路 |
| 查询模式 | QUERY、EXPLAIN、DIMENSION_VALUES、PREVIEW 的正确映射及非法混合参数均覆盖 |
| 版本切换 | 旧版新请求返回过期；已受理查询可读；旧 optionId 不被自动带入新版本 |
| 超时恢复 | 同 execution 的等价请求生成同键；网络重试不重复受理；改参数、改版本或新 execution 得到不同键 |
| 时间与精度 | 北京时间与 UTC offset 等价输入产生一致出站请求；金额、BIGINT 和 null 不丢语义 |
| 结果规模 | 大结果分页、上游截断、单行超限、超大 HTTP 响应和源码分页均有明确行为 |
| YAML 维护 | 使用现有内联与顶层格式的 fixture，完成草稿修改、摘要校验和确认卡片；不自动发布 |
| YAML 错误 | 重复键、语法错误、多文档、摘要冲突、路径逃逸和解析器资源限制均被识别 |
| 独立性 | dbt 离线可维护既有 Git 草稿；Git 关闭不阻断分析；本次无平台或 dbt 服务端改动 |

开发时先运行新模块与受影响模块的 Maven 测试，随后从父 POM 验证集成。例如新增模块后运行 `mvn -pl backend/agent-dbt,backend/agent-skill -am verify`，再运行覆盖服务器装配和会话工具流程的 `mvn -pl backend/agent-server -am verify`。以仓库实际 Maven 入口为准，不能因未安装全局 Maven而跳过已有 wrapper。

协议测试使用真实 v2 结构的受控 HTTP fixture；会话测试验证 Skill 激活、工具记录和 Git 候选流程。真实服务只读冒烟需已有活动发布并记录 projectId、releaseId 和 queryId；模拟服务通过不能声称真实服务已联调。缺少本机运行配置时报告具体限制，不替用户发布业务项目或推送业务 YAML 来制造测试数据。

交付时补充中文配置及使用说明，说明如何启用、绑定项目、维护 YAML、分析数据、识别未发布变更和排查版本过期。

## 9. 评审与后续

本轮只产出设计文档。用户确认本文后，依照 Superpowers writing-plans 编写实现计划，再按其执行选择实施。实现中保留工作区原有的前端和上下文模块未提交修改。
