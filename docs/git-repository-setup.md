# 独立 Git 仓库接入与运维

## 边界与持久化

Git 是独立部署的组件，可使用现有 Git 托管服务，也可部署 SSH 加裸仓库。所有会话共享一个远端 `main`，Agent 只创建候选并在用户点击卡片后推送；“已推送”仅表示远端已接受提交。下游指标平台的拉取、校验和发布由运维负责。

在聚合工作区中，本地 Git 已由根仓库 Docker Compose 与 PostgreSQL、Redis 一起管理。执行根目录的 `infra/git/Initialize-GitService.ps1` 即可构建、初始化和启动；具体步骤见[根仓库 Git Docker 部署说明](../../infra/git/README.md)。下文保留独立服务器部署的通用说明。

以下路径、域名、公钥和身份均为示例，需要替换为部署环境配置，不能把真实私钥、令牌或连接信息提交到源码。假定运维已安装 Git、OpenSSH，创建专用 `git` 服务用户并准备好它拥有的 `/srv/git` 持久卷。该用户仅开放 Git SSH 命令，仓库访问范围由托管服务权限或运维维护的受限命令入口控制。

必须持久化并备份：

- Git 服务端的 `/srv/git/metrics.git`：对象、引用、保护配置与 hook。
- Agent 服务端工作根目录（`DATA_AGENT_GIT_WORKSPACE_ROOT`）及 PostgreSQL：草稿、冻结候选与确认记录成对备份，单实例运行。
- SSH 主机密钥和访问授权配置：独立于容器生命周期管理；密钥轮换不能通过关闭主机校验解决。

## 初始化 main

空裸仓库不能作为当前 Agent 的起点。以下 Linux 示例由运维在 Git 服务器上执行一次；已有仓库应保留历史并确认 `main` 存在，不要重复初始化或覆盖引用。

```sh
sudo -u git git init --bare --initial-branch=main /srv/git/metrics.git
sudo -u git git --git-dir=/srv/git/metrics.git config receive.denyNonFastForwards true
sudo -u git git --git-dir=/srv/git/metrics.git config receive.denyDeletes true

# 用独立初始化目录创建一个实际提交，不使用 Agent 的工作目录。
sudo -u git git clone /srv/git/metrics.git /srv/git/metrics-bootstrap
sudo -u git git -C /srv/git/metrics-bootstrap -c user.name='Repository Bootstrap' -c user.email='bootstrap@example.invalid' commit --allow-empty -m 'Initialize main'
sudo -u git git -C /srv/git/metrics-bootstrap push origin main
sudo -u git git --git-dir=/srv/git/metrics.git symbolic-ref HEAD refs/heads/main
sudo -u git git --git-dir=/srv/git/metrics.git rev-parse --verify refs/heads/main
```

最后一个命令必须返回有效提交 ID。真实业务文件可以由维护者正常提交；仓库内容不会被 Agent 当脚本执行。初始化目录与 Agent 工作目录用途不同，后续按运维流程归档即可。

`receive.denyNonFastForwards=true` 拒绝历史改写，`receive.denyDeletes=true` 拒绝分支删除。使用托管服务时，对 `main` 设置等价保护：允许 Agent 身份正常快进推送，禁止强制推送和删除；不要配置与当前直接确认推送流程冲突的“必须 PR”规则。服务端管理员仍可绕过文件级保护，因此管理员权限与备份恢复流程需要独立管理。

## SSH 凭据引用

Agent 运行账户只引用由部署系统挂载的密钥。以下是该账户的 `~/.ssh/config` 示例，不是 Git 仓库内的文件：

```sshconfig
Host agent-metrics-git
    HostName git.example.invalid
    User git
    IdentityFile /run/secrets/agent_git_private_key
    IdentitiesOnly yes
    BatchMode yes
    StrictHostKeyChecking yes
    UserKnownHostsFile /run/secrets/agent_git_known_hosts
```

私钥只允许运行账户读取（例如权限 `0600`），`~/.ssh` 使用 `0700`，配置文件使用 `0600`。通过可信渠道核对 SSH 主机指纹后生成 `known_hosts` 并只读挂载；不要盲目信任首次扫描结果。对应公钥由运维授权到 Git 服务的专用身份，限定到目标仓库并禁用交互 shell、PTY、转发等额外能力。若直接维护 `authorized_keys`，需要配合受限 Git 命令入口；单独加 `restrict` 选项并不能限定可访问仓库。

服务端环境变量示例：

```text
DATA_AGENT_GIT_ENABLED=true
DATA_AGENT_GIT_REMOTE_URI=ssh://git@agent-metrics-git/srv/git/metrics.git
DATA_AGENT_GIT_WORKSPACE_ROOT=/var/lib/data-agent/git
```

Agent 的 Git 子进程会清理 `GIT_SSH_COMMAND`、`GIT_SSH` 等覆盖变量，并禁用全局 Git 配置；因此使用运行账户的 SSH 配置和部署挂载的凭据，不依赖临时命令环境覆盖。可由运维以同一运行账户执行 `git ls-remote ssh://git@agent-metrics-git/srv/git/metrics.git refs/heads/main`，验证非交互读取；确认推送权限应使用受控测试仓库验收。

Agent 的完整参数、默认关闭行为、数据库和恢复机制见 [Git 运行配置与接口](agent-git-runtime.md)。

## post-receive 运维接点

可在 Git 组件的 `post-receive` 或托管平台等价 webhook 处接入下游。Git 会在引用更新后调用 `post-receive`；其结果不能撤销已经接受的提交，输出会传回推送客户端。参见 [Git 官方 hook 文档](https://git-scm.com/docs/githooks#post-receive)。

本系统约定 hook **只快速投递或入队**：传递仓库标识、旧提交、新提交和 `refs/heads/main`，投递后立即返回。独立 worker 再异步拉取仓库并执行指标平台更新；按仓库加新提交 ID 去重并保留失败重试及告警。不要在 hook 中同步等待 HTTP 指标刷新或下游发布完成，也不要把令牌或内部错误写到 hook 的标准输出。

Git 推送响应仍需等待服务端协议结束，hook 并非天然异步；快速返回需要由部署方保证。Agent 不等待、不轮询下游，不消费指标平台成功回调。若队列或下游故障，Git 提交仍可能已接受，应由运维按提交 ID 补投和核实，下游故障不能被解释成 Git 未推送。

## 接入验收与恢复

1. 核对 `main` 已存在、裸仓库和 Agent 目录为持久卷、数据库可用、仅一个 Agent 实例。
2. 核对专用身份可读取和正常快进推送，服务端拒绝非快进与删除；在测试仓库进行拒绝场景验证。
3. 启用 Agent 后准备草稿、生成卡片，核对摘要和完整差异，再点击确认。卡片应显示“已推送”，远端 `main` 只增加一次提交；重复确认不重复新增提交。
4. 验证 `SUBMITTING` / `VERIFYING` 会在短时自动查询后停止；网络恢复后点击“核实结果”。需要重新准备的错误应生成新草稿与新卡片，不能对已失效内容重试。
5. 验证运维 hook 快速返回并由异步 worker 更新下游，检查投递、重试及告警；这不改变 Agent 的成功终点。

仓库测试 `GitWebIntegrationTest` 使用独立本地裸仓库与 PostgreSQL，覆盖真实 Tool 准备、文件修改、候选、最终回答保留卡片、HTTP 确认、唯一提交推送；不依赖或调用指标平台。生产 SSH、持久卷、访问权限及运维队列需要部署现场验收。
