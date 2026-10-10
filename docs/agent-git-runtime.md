# Git 开发运行配置与接口

## 启用与固定目标

默认 `data-agent.git.enabled=false`。启用后，先创建 Agent 项目，通过 `/api/v1/agent-projects/{projectId}/development-config` 配置受控仓库、preview/production executionBinding 和 configVersion，再读取真实 Git 分支并创建绑定该项目、分支的开发会话。普通会话不自动绑定仓库。会话绑定后不可换分支或迁移项目，每个会话拥有独立本地目录。

服务端使用运行账户的 Git/SSH 身份；凭据不能进入模型参数、API 返回值或项目仓库地址。仓库地址仅从项目开发配置取得，不再提供全局默认仓库环境变量。预览与正式交付需同时启用 dbt v3，配置服务端固定 base URL 和 service token。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| DATA_AGENT_GIT_WORKSPACE_ROOT | ./data/git | 私有会话目录及历史快照根目录，生产应使用绝对目录 |
| DATA_AGENT_GIT_EXECUTABLE | git | 服务端 Git 可执行程序 |
| DATA_AGENT_GIT_AUTHOR_NAME | Data Agent | 固定提交作者名称 |
| DATA_AGENT_GIT_AUTHOR_EMAIL | data-agent@localhost | 固定提交作者邮箱 |
| DATA_AGENT_GIT_COMMAND_TIMEOUT | 30s | 单条 Git 子进程截止时间 |
| DATA_AGENT_GIT_MAX_COMMAND_OUTPUT_BYTES | 4194304 | 单条命令输出字节上限 |
| DATA_AGENT_GIT_MAX_FILE_BYTES | 524288 | 单个 UTF-8 文件字节上限 |
| DATA_AGENT_GIT_MAX_FILES_PER_OPERATION | 20 | 单次结构化编辑文件数上限 |
| DATA_AGENT_GIT_MAX_CANDIDATE_FILES | 100 | 单次完整工程差异文件数上限 |
| DATA_AGENT_GIT_MAX_CANDIDATE_BYTES | 5242880 | 差异涉及的新旧内容总字节上限 |
| DATA_AGENT_GIT_MAX_DIFF_BYTES | 2097152 | 完整 diff 字节上限 |

构造 Bean 不 clone 仓库。文件访问按需初始化会话目录。仅运行单实例；后台恢复使用受管调度线程读取持久操作，不依赖模型调用或 SSE 连接。数据库保存目标、输入摘要和状态，不保存未推送文件正文；本地未保存修改丢失必须显示 `LOCAL_INPUT_LOST`，不能根据聊天内容重建并报告成功。

## 模型工具

模型必须先读取相应 Skill。Git 开启时注册五个文件工具及 `development_save`；同时开启 dbt 后增加 `development_preview`、`development_deliver`、`development_status`。工具不允许出现在 initial 中。所有目标从可信 executionId 解析到固定会话，模型不能指定 workspaceId、sessionId、仓库、分支或部署绑定。

- `git_files_list`：offset/limit 分页，limit 最大 100，返回当前 revision。
- `git_files_search`：offset/limit 分页，limit 最大 50，每条命中文本最多 300 字符。
- `git_file_read`：startLine 从 1 开始，lineCount 最大 200；最多 20000 字符，sha256 始终是完整文件摘要。超长单行明确标记 truncated。
- `git_diff`：字符分页，limit 最大 20000、默认 12000；文件摘要另以 fileOffset/fileLimit 分页。截断差异不可作为完整交付确认。
- `git_files_apply`：必须提供 expectedRevision，支持 CREATE/UPDATE/DELETE；更新和删除必须提供完整预期 SHA-256。
- `development_save`：固定 revision 与提交说明，保存完整工程并推送功能分支；不执行推送前 dbt 校验。
- `development_preview`：必要时先保存，核实远端接收后提交固定 SHA 的构建，只有预览部署成功才成功。
- `development_deliver`：必须确认功能分支全部差异、两端 SHA 和范围摘要，创建真实 merge commit、推送 main，再构建和部署该合并 SHA。
- `development_status`：分别展示本地、Git、构建、部署和操作状态；查询不推进发布。

保存、预览、交付的稳定键来自可信 InvocationContext。HTTP 与工具调用同一应用用例；响应丢失时按原键/原输入核实，不生成新输入隐式重试。显式恢复需当前操作 version 和新恢复幂等键。

## HTTP 与契约

完整且唯一的公开契约为 `contracts/openapi.yaml`，构建打包后由 `/api/v1/development/contract` 返回 `build-deployment-v3` 及完整 paths。

| 路径 | 用途 |
| --- | --- |
| GET `/api/v1/agent-projects` | cursor/limit 项目分页 |
| GET/PUT `/api/v1/agent-projects/{projectId}/development-config` | 开发配置查询及版本保护更新 |
| GET/POST `/api/v1/agent-projects/{projectId}/branches` | 读取真实远端分支或持久创建动作 |
| POST `/api/v1/agent-sessions` | 可选 development 绑定项目及分支 |
| GET `/api/v1/agent-sessions/{sessionId}/development` | 开发状态与完整交付确认范围 |
| POST 前缀 `/save`、`/preview`、`/deliver` | 202 返回持久操作，后台独立继续 |
| GET 前缀 `/operations/{operationId}` | 查询同会话操作 |
| POST 前缀 `/operations/{operationId}/recover` | 版本保护的显式恢复 |
| GET/POST 前缀 `/files` | 文件目录/修订与结构化修改 |
| GET 前缀 `/file`、`/diff` | 文件正文/完整本地差异 |

原 git-workspaces、metric-branches、Git 卡片确认/重试/取消/核实写接口已退役。历史 Git 卡片仅保留详情、files、diff 三个 GET；材料缺失或损坏时 reviewAvailable=false，不能解释成空差异。消息请求不再接受 workspaceId。

数据库升级保留旧审计材料，不自动继续旧候选。新保存成功仅证明 Git 已接收；main 合并成功也不等于正式部署成功。构建失败或部署失败可恢复，不能把先前阶段成功当作最终交付成功。
