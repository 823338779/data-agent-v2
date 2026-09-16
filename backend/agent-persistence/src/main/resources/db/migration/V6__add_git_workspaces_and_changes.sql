create table git_workspace (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 workspace_id text not null,
 conversation_session_id text not null,
 repository_id text not null,
 storage_key text,
 base_commit text,
 draft_revision bigint not null default 0,
 draft_digest text,
 active_change_id text,
 status text not null,
 version bigint not null default 0,
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false
);
comment on table git_workspace is '会话持久化 Git 草稿工作区';
comment on column git_workspace.id is '数据库内部自增主键';
comment on column git_workspace.tenant_id is '固定运行租户';
comment on column git_workspace.workspace_id is '服务端生成的工作区业务标识';
comment on column git_workspace.conversation_session_id is '可信执行上下文解析出的会话标识';
comment on column git_workspace.repository_id is '服务端固定配置仓库标识';
comment on column git_workspace.storage_key is '服务端生成的 clone 存储键，显式重新开始会更换；旧目录保留。';
comment on column git_workspace.base_commit is '草稿或候选基线提交，重试不得改写';
comment on column git_workspace.draft_revision is '单调递增草稿修订号，任何实际编辑均使旧候选失效。';
comment on column git_workspace.draft_digest is '工作目录全部文件的摘要，排除 .git；检测服务重启或外部修改。';
comment on column git_workspace.active_change_id is '当前候选 ID；提交和核实阶段同时作为工作区编辑屏障。';
comment on column git_workspace.status is 'READY 可使用，MUTATING 为文件操作屏障，BROKEN 必须显式重新开始';
comment on column git_workspace.version is 'CAS 单调版本；重复按钮只接受已完成转换的原版本。';
comment on column git_workspace.created_at is '创建时间';
comment on column git_workspace.updated_at is '最后修改时间';
comment on column git_workspace.deleted_at is '逻辑删除时间，未删除为空';
comment on column git_workspace.is_deleted is '逻辑删除标记';
create table git_change (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 change_id text,
 workspace_id text not null,
 conversation_session_id text not null,
 conversation_message_id text,
 repository_id text not null,
 branch text,
 base_commit text,
 tree_id text,
 snapshot_storage_key text,
 snapshot_digest text,
 candidate_digest text,
 summary text,
 commit_message text,
 draft_revision bigint not null default 0,
 file_count integer,
 status text not null,
 confirmed_at timestamptz,
 confirmed_by text,
 author_name text,
 author_email text,
 commit_time timestamptz,
 commit_id text,
 prepare_request_key text,
 request_digest text,
 error_code text,
 version bigint not null default 0,
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false
);
comment on table git_change is '冻结 Git 候选、确认授权及推送恢复记录';
comment on column git_change.id is '数据库内部自增主键';
comment on column git_change.tenant_id is '固定运行租户';
comment on column git_change.change_id is '服务端生成的候选标识，卡片通过此标识关联';
comment on column git_change.workspace_id is '服务端生成的工作区业务标识';
comment on column git_change.conversation_session_id is '可信执行上下文解析出的会话标识';
comment on column git_change.conversation_message_id is '短事务关联的 assistant 消息标识';
comment on column git_change.repository_id is '服务端固定配置仓库标识';
comment on column git_change.branch is '目标分支固定为 main';
comment on column git_change.base_commit is '草稿或候选基线提交，重试不得改写';
comment on column git_change.tree_id is '冻结完整文件树的 Git tree 对象标识';
comment on column git_change.snapshot_storage_key is '不可变审阅材料存储键，不得向客户端暴露。';
comment on column git_change.snapshot_digest is '完整快照文件 SHA-256，文件缺失或不符时禁止确认。';
comment on column git_change.candidate_digest is '绑定仓库、main、基线、tree、说明和 diff 的候选摘要。';
comment on column git_change.summary is '已展示并冻结的业务变更摘要';
comment on column git_change.commit_message is '已展示并冻结的原始提交说明';
comment on column git_change.draft_revision is '单调递增草稿修订号，任何实际编辑均使旧候选失效。';
comment on column git_change.file_count is '候选全部变更文件数量';
comment on column git_change.status is '候选授权与推送状态：PENDING_CONFIRMATION、SUBMITTING、VERIFYING、PUSHED、RECONFIRM_REQUIRED、INVALIDATED、CANCELLED、FAILED';
comment on column git_change.confirmed_at is '用户确认时间，空值代表没有提交授权。';
comment on column git_change.confirmed_by is '固定运行身份，只有确认接口能够写入。';
comment on column git_change.author_name is '冻结的提交作者名，同时用作 committer 名称';
comment on column git_change.author_email is '冻结的提交作者邮箱，同时用作 committer 邮箱';
comment on column git_change.commit_time is '首次确认固定的秒级 UTC 提交时间，重试不得覆盖。';
comment on column git_change.commit_id is 'push 前登记的确定性 commit ID，尚未生成时为空';
comment on column git_change.prepare_request_key is '工作区内唯一的 Tool 准备请求幂等键';
comment on column git_change.request_digest is '准备请求内容摘要，同一幂等键不能用于不同内容。';
comment on column git_change.error_code is '稳定脱敏错误码，不保存 Git stderr';
comment on column git_change.version is 'CAS 单调版本；重复按钮只接受已完成转换的原版本。';
comment on column git_change.created_at is '创建时间';
comment on column git_change.updated_at is '最后修改时间';
comment on column git_change.deleted_at is '逻辑删除时间，未删除为空';
comment on column git_change.is_deleted is '逻辑删除标记';
create unique index uk_git_workspace_id on git_workspace(workspace_id);
create unique index uk_git_workspace_session on git_workspace(tenant_id, conversation_session_id, repository_id);
create unique index uk_git_change_id on git_change(change_id);
create unique index uk_git_change_prepare on git_change(tenant_id, workspace_id, prepare_request_key);
create index ix_git_change_recovery on git_change(status);
alter table git_change add column confirmation_version bigint, add column retry_version bigint, add column cancel_version bigint;
comment on column git_change.confirmation_version is '首次确认按钮请求版本，后续重复请求返回原任务';
comment on column git_change.retry_version is '最近一次重试请求版本，用于 CAS 去重';
comment on column git_change.cancel_version is '取消请求版本，用于终态幂等';
