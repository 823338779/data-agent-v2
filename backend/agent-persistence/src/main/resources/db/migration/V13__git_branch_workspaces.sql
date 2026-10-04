-- 工作区是独立草稿，创建会话不再拥有其唯一身份或生命周期。
drop index uk_git_workspace_session;
alter table git_workspace add column project_id text, add column branch_id text,
 add column git_ref text, add column legacy_read_only boolean not null default false,
 add column creation_key text, add column creation_digest text;
comment on column git_workspace.project_id is 'dbt 服务的受控逻辑项目，历史工作区未登记时为空';
comment on column git_workspace.branch_id is 'dbt 服务生成的固定分支实例身份，历史 main 恢复工作区为空';
comment on column git_workspace.git_ref is '固定目标完整 Git ref，创建后不可随会话选择改变';
comment on column git_workspace.legacy_read_only is '旧 main 工作区仅供历史读取与恢复，不接受新的开发写入';
comment on column git_workspace.creation_key is '创建会话内的幂等键，同键恢复原工作区';
comment on column git_workspace.creation_digest is '固定创建输入的摘要，防止同键修改分支或基线';
comment on column git_workspace.conversation_session_id is '最初创建会话，仅作历史审计；关联关系由独立表维护';
comment on table git_workspace is '独立 Git 草稿及固定分支身份，可被多个会话关联';
update git_workspace set git_ref='refs/heads/main',legacy_read_only=true;
create unique index uk_git_workspace_creation on git_workspace(tenant_id,conversation_session_id,creation_key);

-- 关联是可选上下文，不代表人员权限；删除会话不会级联删除工作区。
create table git_session_workspace (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 conversation_session_id text not null,
 workspace_id text not null references git_workspace(workspace_id),
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 unique(tenant_id,conversation_session_id,workspace_id)
);
comment on table git_session_workspace is '会话与独立工作区多对多关联，不承担授权';
comment on column git_session_workspace.id is '数据库内部自增主键';
comment on column git_session_workspace.tenant_id is '固定运行租户';
comment on column git_session_workspace.conversation_session_id is '关联会话标识，不代表工作区所有权';
comment on column git_session_workspace.workspace_id is '独立工作区业务标识';
comment on column git_session_workspace.created_at is '关联创建时间';
comment on column git_session_workspace.updated_at is '关联更新时间';
comment on column git_session_workspace.deleted_at is '逻辑解除关联时间';
comment on column git_session_workspace.is_deleted is '关联是否已逻辑删除';
insert into git_session_workspace(tenant_id,conversation_session_id,workspace_id)
 select tenant_id,conversation_session_id,workspace_id from git_workspace;

-- 执行受理时冻结目标；后续会话选择或关联变化不能改写已受理工具上下文。
create table git_execution_workspace (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 execution_id text not null unique,
 conversation_session_id text not null,
 workspace_id text not null references git_workspace(workspace_id),
 project_id text not null,
 branch_id text not null,
 git_ref text not null,
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false
);
comment on table git_execution_workspace is '执行受理时冻结的工作区与分支上下文';
comment on column git_execution_workspace.id is '数据库内部自增主键';
comment on column git_execution_workspace.tenant_id is '固定运行租户';
comment on column git_execution_workspace.execution_id is '唯一执行身份，同执行不得重新绑定目标';
comment on column git_execution_workspace.conversation_session_id is '受理时会话标识，用于审计与幂等核对';
comment on column git_execution_workspace.workspace_id is '受理时选定的独立工作区';
comment on column git_execution_workspace.project_id is '固定 dbt 项目标识';
comment on column git_execution_workspace.branch_id is '固定 dbt 分支实例身份';
comment on column git_execution_workspace.git_ref is '固定远端完整引用，不能由工具参数覆盖';
comment on column git_execution_workspace.created_at is '冻结时间';
comment on column git_execution_workspace.updated_at is '审计更新时间，不代表允许重绑定';
comment on column git_execution_workspace.deleted_at is '保留审计字段，不随会话删除清理';
comment on column git_execution_workspace.is_deleted is '保留审计字段，执行绑定按历史保留';
