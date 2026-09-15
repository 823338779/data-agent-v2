# Git 仓库维护运行配置与接口

## 启用

默认 `data-agent.git.enabled=false`。部署时设置 `DATA_AGENT_GIT_ENABLED=true`，并通过服务端配置提供 `DATA_AGENT_GIT_REMOTE_URI`。服务器需要可执行的 Git 和访问目标仓库的服务端身份；凭据不能进入模型参数、卡片或仓库配置文件。所有会话共用这个远端的 `main`。

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

只有以下七个工具：`git_workspace_prepare`、`git_files_list`、`git_files_search`、`git_file_read`、`git_files_apply`、`git_diff`、`git_change_prepare`。所有 Schema 拒绝未知字段，运行时再次检查字段、类型、整数范围和相对路径。会话与幂等键来自服务端 InvocationContext。

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
{"changeId":"gc_示例","version":0,"status":"PENDING_CONFIRMATION","branch":"main","baseCommit":"提交对象标识","summary":"调整指标","commitMessage":"update metrics","commitId":null,"files":[{"fileId":"0","status":"M","path":"metrics.yml"}],"errorCode":null,"errorSummary":null}
```

确认/重试返回后，前端通过 GET 详情刷新异步状态；后台不会发起模型调用。卡片发布复用现有 message_update，不引入独立 Git SSE 事件。

状态为 `PENDING_CONFIRMATION`、`SUBMITTING`、`VERIFYING`、`PUSHED`、`RECONFIRM_REQUIRED`、`INVALIDATED`、`CANCELLED`、`FAILED`。`PUSHED` 是本能力成功终点；不代表指标平台已生效。

错误使用现有 ProblemDetail，`code` 是稳定 Git 错误码。冲突/失效 409，当前会话资源不存在 404，未配置或远端执行不可用 503，资源超限 413，子进程超时 504，无效请求/不支持内容 400。响应只使用固定错误码说明，不返回 Git stderr。

## 终答边界

`ModelInvocationCoordinator.completeFinalAnswer` 在原终答落库位置调用可选的 `GitFinalAnswerComposer`，将当前 assistant 消息已登记的 Git 卡片占位符补回最终文字，并同时写入流检查点。否则正常模型回答会覆盖工具发布的占位符。此接入不修改 ReAct 循环、AgentTool SPI、执行状态机、artifact 投影或 artifact 终答行为。Git 关闭时该服务不存在，原终答行为保持不变。
