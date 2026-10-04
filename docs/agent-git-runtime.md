# Git 仓库维护运行配置与接口

## 启用

默认 `data-agent.git.enabled=false`。部署时设置 `DATA_AGENT_GIT_ENABLED=true`，并通过服务端配置提供 `DATA_AGENT_GIT_REMOTE_URI`。服务器需要可执行的 Git 和访问目标仓库的服务端身份；凭据不能进入模型参数、卡片或仓库配置文件。分支开发还需开启 dbt 并配置同一项目及分支服务凭据；`main` 保持生产用途。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| DATA_AGENT_GIT_REPOSITORY_ID | metrics | 持久化使用的固定仓库业务标识 |
| DATA_AGENT_GIT_WORKSPACE_ROOT | ./data/git | 私有工作区与候选存储根目录，生产环境应配置绝对目录 |
| DATA_AGENT_GIT_EXECUTABLE | git | 服务端 Git 可执行程序 |
| DATA_AGENT_GIT_AUTHOR_NAME | Data Agent | 候选冻结时保存的作者名称 |
| DATA_AGENT_GIT_AUTHOR_EMAIL | data-agent@localhost | 候选冻结时保存的作者邮箱 |
| DATA_AGENT_GIT_COMMAND_TIMEOUT | 30s | 单条 Git 子进程截止时间 |
| DATA_AGENT_GIT_MAX_COMMAND_OUTPUT_BYTES | 4194304 | 单条命令输出字节上限 |
| DATA_AGENT_GIT_MAX_FILE_BYTES | 524288 | 单个 UTF-8 文件字节上限 |
| DATA_AGENT_GIT_MAX_FILES_PER_OPERATION | 20 | 单次结构化编辑文件数上限 |
| DATA_AGENT_GIT_MAX_CANDIDATE_FILES | 100 | 候选变更文件数上限 |
| DATA_AGENT_GIT_MAX_CANDIDATE_BYTES | 5242880 | 候选涉及的新旧内容总字节上限 |
| DATA_AGENT_GIT_MAX_DIFF_BYTES | 2097152 | 完整冻结 diff 字节上限 |

配置对应 `application-business.yml` 中的 `data-agent.git`。开启而未提供远端时启动失败，错误为 `GIT_NOT_CONFIGURED`；构造 Bean 不会 clone 或创建工作目录。关闭时不注册 Git 工具、Skill、HTTP 控制器或应用服务，也不会创建工作区；既有持久数据不自动删除。

开启开关会在 server 装配层为 `data-agent.tool.maximum` 加入七个 Git 草稿工具。其他 maximum 名称继续严格校验；不允许把 Git 工具配置到 `initial`。模型必须先读取 `git-repository-maintenance` Skill 才能在该次执行看到工具。关闭开关后，不应手工保留 Git 名称在 maximum 中。

数据库和工作目录应成对持久化，仅运行一个服务实例。不要在未完成候选存在时更换固定仓库身份或远端。后台使用独立异步串行执行器；关闭时停止排队并中断正在执行的任务，启动时只核实遗留已确认记录一次。恢复不会自动确认、重新生成 commit 或查询指标平台。

工具超时按一次候选冻结可能包含的多条 Git 命令预算推导，而不是默认 30 秒；现有 execution 硬截止时间仍然是更外层的最终上限。

## 模型工具边界

只有以下七个工具：`git_workspace_prepare`、`git_files_list`、`git_files_search`、`git_file_read`、`git_files_apply`、`git_diff`、`git_change_prepare`。所有 Schema 拒绝未知字段，运行时再次检查字段、类型、整数范围和相对路径。会话与幂等键来自服务端 InvocationContext；工作区由受理时持久化的 executionId 绑定解析，模型不能传入或改变分支、远端、schema 或目录。

- list：offset/limit 分页，limit 最大 100。
- search：offset/limit 分页，limit 最大 50，每条命中文本最多 300 字符。
- read：startLine 从 1 开始，lineCount 最大 200，返回内容最多 20000 字符；sha256 始终是完整文件摘要。单行超长文本会明确 truncated，不支持超长行内部的完整分段读取。
- diff：offset/limit 是字符分页，limit 最大 20000，默认 12000；文件摘要使用 fileOffset/fileLimit 分页。不得将截断 diff 当成完整审阅。
- apply：CREATE/UPDATE/DELETE，更新和删除必须提供完整预期 SHA-256；数量还受服务端上限限制。
- change_prepare：summary、commitMessage、expectedRevision；幂等键不允许模型指定。成功后正常结束对话，等待卡片按钮。

工具响应有界；底层当前仍遍历完整目录或搜索结果再投影分页，首版没有仓库索引或遍历预算。工作区旧 clone、冻结 ref 和未登记快照保留，尚无自动清理。

## 卡片与 HTTP

消息 UI Block 使用既有消息投影和 SSE `message_update`：

```json
{"blockId":"ui_block_示例","type":"git-change-card","schemaVersion":1,"variant":"default","payload":{"changeId":"gc_示例","sessionId":"session_示例"},"displayOrder":0}
```

payload 两个 ID 均由服务器写入。候选、UI Block、正文占位符和消息更新事件在同一数据库短事务内登记。任何发布错误整体回滚；文件冻结在事务之前完成，未登记快照不能确认。

前缀：`/api/v1/agent-sessions/{sessionId}/git-changes/{changeId}`。

| 方法与路径 | 响应 |
| --- | --- |
| GET 前缀 | 200 GitChange 详情 |
| GET 前缀/files | 200 文件摘要数组 |
| GET 前缀/diff?fileId=0 | 200 `{fileId,diff}`，fileId 必须来自登记列表 |
| POST 前缀/confirm | 202 GitChange；重复同版本确认不重复排队 |
| POST 前缀/cancel | 200 GitChange；保留草稿 |
| POST 前缀/retry | 202 GitChange；只重试已确认的 FAILED 候选 |
| POST 前缀/reconcile | 200 GitChange；只读核实，不提交或推送 |

所有 POST 请求为 `{"version":0}`；使用最新详情的真实 version，0 是合法初始版本。reconcile 的版本检查在 Web 边界完成，应用服务仍使用 `reconcile(sessionId, changeId)`。

详情示例（字段含 null，files 不含 diff）：

```json
{"changeId":"gc_示例","version":0,"status":"PENDING_CONFIRMATION","branch":"main","baseCommit":"提交对象标识","summary":"调整指标","commitMessage":"update metrics","commitId":null,"reviewAvailable":true,"files":[{"fileId":"0","status":"M","path":"metrics.yml"}],"errorCode":null,"errorSummary":null}
```

确认/重试返回后，前端通过 GET 详情刷新异步状态；后台不会发起模型调用。卡片发布复用现有 message_update，不引入独立 Git SSE 事件。

状态为 `PENDING_CONFIRMATION`、`SUBMITTING`、`VERIFYING`、`PUSHED`、`RECONFIRM_REQUIRED`、`INVALIDATED`、`CANCELLED`、`FAILED`。`PUSHED` 是本能力成功终点；不代表指标平台已生效。

错误使用现有 ProblemDetail，`code` 是稳定 Git 错误码。冲突/失效 409，当前会话资源不存在 404，未配置或远端执行不可用 503，资源超限 413，子进程超时 504，无效请求/不支持内容 400。响应只使用固定错误码说明，不返回 Git stderr。

## 终答边界

`ModelInvocationCoordinator.completeFinalAnswer` 在原终答落库位置调用可选的 `GitFinalAnswerComposer`，将当前 assistant 消息已登记的 Git 卡片占位符补回最终文字，并同时写入流检查点。否则正常模型回答会覆盖工具发布的占位符。此接入不修改 ReAct 循环、AgentTool SPI、执行状态机。Git 关闭时该服务不存在，原终答行为保持不变。


### 审阅可用性与基础检查

详情和按钮响应增加 `reviewAvailable`：完整快照可读时为 true；仅当已知快照缺失或摘要损坏时为 false，此时 `files=null`，明确表示无法审阅，不能解释为空变更。状态、版本、commitId 和错误字段仍来自数据库，已核实的 PUSHED 不因材料损坏而改变。确认和重试禁用，取消及结果核实仍遵循原归属、版本与状态规则；`/files`、`/diff` 仍对损坏材料返回 GIT_SNAPSHOT_CORRUPT（409）。其他异常不作成功响应。

卡片说明冻结时通过变更文件 UTF-8、文件与 Git 变更完整性及大小限制检查；这仅是冻结当时的基础检查，不表示当前材料仍可用，也未进行指标业务口径校验。

工作根目录在配置边界转为绝对规范化路径，相对路径以服务进程启动目录为基准。启动就绪前通过短数据库查询固定不可变的遗留 ID 集合，再向受管串行执行器投递一次后台任务，仅核实该批记录，避免选入就绪后的新确认；不等待远端完成而阻塞 readiness；后台异常记录固定安全日志，不输出异常详情、路径或凭据。恢复不自动确认或推送。

## 分支工作区协作与迁移

先在“开发工作区”选择来源分支及基线 SHA，新建开发分支或在已有开发分支上建立独立草稿。多个会话可以关联同一个 workspaceId，共享同一草稿和修订；也可以在同一 branchId 下各建工作区，保留独立文件目录。每个会话可以切换不同工作区，消息受理时保存不可变绑定。正在运行的工具与既有卡片不会跟随切换。

分支由 dbt-service 登记。Agent 使用受控项目和 `DATA_AGENT_DBT_BRANCH_SERVICE_TOKEN` 访问分支写接口，禁止把 remote、凭据、物理目录或 schema 作为请求参数。前端可通过 `VITE_METRIC_PLATFORM_URL` 配置预览链接基址。需要分支服务支持 `branch-development-v1`、`draft-validation-v2` 及 publication 当前配置/工具链版本。

| API | 用途 |
| --- | --- |
| GET/POST `/api/v1/git-workspaces` | 按 sessionId 列举关联；按 sessionId、branchId、baseCommitSha、idempotencyKey 创建草稿 |
| GET `/api/v1/git-workspaces/{workspaceId}` | 查看固定目标与草稿状态 |
| PUT `/api/v1/agent-sessions/{sessionId}/git-workspaces/{workspaceId}` | 幂等关联已有草稿 |
| POST `/api/v1/git-workspaces/{workspaceId}/sync` | 携带 expectedRevision 三方同步；冲突保留原草稿 |
| GET/POST `/api/v1/metric-branches` | 列举分支或从明确来源创建 |
| POST `/api/v1/metric-branches/register` | 登记并核实已有远端 ref |
| GET/DELETE `/api/v1/metric-branches/{branchId}` | 查看或按 expectedVersion 删除开发分支 |
| GET `/api/v1/metric-branches/{branchId}/releases` | 查看构建列表 |
| GET `/api/v1/metric-branches/{branchId}/releases/{releaseId}` | 查看固定构建状态、安全摘要 |

创建工作区的示例：

```json
{"sessionId":"session_示例","branchId":"服务登记的分支标识","baseCommitSha":"明确的40位提交SHA","idempotencyKey":"本次创建的稳定键"}
```

受理消息增加可空 workspaceId。无工作区可以普通聊天；Git 工具没有绑定时明确失败。同 idempotencyKey 改变 workspaceId 会返回 409。会话归档不删除工作区或远端分支。

共享分支上有人先推送时，旧候选需要同步、重新验证和确认。同步成功递增修订，撤销原候选和校验；冲突保留旧目录及材料。push 先证明快进关系，再用明确 expected SHA 的 lease 完成远端比较与交换，禁止覆盖远端历史。响应丢失时只核实原分支是否包含固定 commitId，不生成新提交。

V13 迁移旧 session 工作区为独立身份并回填关联，旧 main 工作区标为只读；历史候选仍可查看和核实已推送结果，不允许新确认或重试推生产。升级前应处理未完成的生产候选；升级后未推送的历史材料应人工核对并迁至新开发草稿。V14 保存候选及校验的分支和配置上下文。

创建失败的 MUTATING/BROKEN 草稿会保留身份，同键重试不会覆盖材料；可以刷新后创建新的独立草稿。删除分支后旧工作区不可继续开发；同名 ref 重新登记是新 branchId，不能复用旧卡片。草稿和旧 clone 尚无自动清理。

恢复入口为 `POST /api/v1/git-workspaces/{workspaceId}/restart`，必须带 `expectedRevision`；工作区面板为损坏/中断草稿提供按钮，原目录留存。分支工具 `git_files_apply` 及 `git_workspace_prepare(restartDraft=true)` 同样核对修订，旧修订不允许修改任何文件。

同步冲突返回 `conflicts` 三方完整有界正文。人工决议通过 `POST /api/v1/git-workspaces/{workspaceId}/sync/resolve` 提交原 `expectedRevision`、`remoteCommitSha` 与完整 `resolutions` 路径集合（正文为 null 表示删除）。服务重新核对远端与原草稿，成功后才推进基线、递增修订并撤销旧确认；必须重新完成定义验证和确认。
