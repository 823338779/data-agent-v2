-- 开发上下文和恢复意图；本地文件不写入数据库。
create table development_project (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 project_id text not null unique,
 repository text not null,
 config_json text not null,
 version bigint not null,
 enabled boolean not null,
 diagnostic text
);
comment on table development_project is '开发上下文与动作持久事实，保留历史';
comment on column development_project.id is '数据库内部主键';
comment on column development_project.tenant_id is '固定运行租户';
comment on column development_project.created_at is '创建时间';
comment on column development_project.updated_at is '最近状态更新时间';
comment on column development_project.deleted_at is '审计删除时间';
comment on column development_project.is_deleted is '逻辑删除标志，历史操作不自动删除';
comment on column development_project.project_id is 'Agent 项目身份';
comment on column development_project.repository is '固定受控仓库，不含凭据';
comment on column development_project.config_json is '当前环境配置 JSON，不含文件正文或凭据';
comment on column development_project.version is '配置乐观锁版本';
comment on column development_project.enabled is '项目是否允许新开发操作';
comment on column development_project.diagnostic is '旧多仓库或来源不全的迁移诊断';
create table development_session (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 session_id text not null unique,
 project_id text not null,
 repository text not null,
 branch_name text not null,
 baseline_sha text not null,
 config_snapshot text not null,
 storage_key text not null,
 revision bigint not null,
 version bigint not null,
 local_state text not null,
 local_digest text,
 pushed_sha text,
 selected_build_id text
);
comment on table development_session is '开发上下文与动作持久事实，保留历史';
comment on column development_session.id is '数据库内部主键';
comment on column development_session.tenant_id is '固定运行租户';
comment on column development_session.created_at is '创建时间';
comment on column development_session.updated_at is '最近状态更新时间';
comment on column development_session.deleted_at is '审计删除时间';
comment on column development_session.is_deleted is '逻辑删除标志，历史操作不自动删除';
comment on column development_session.session_id is '固定开发会话身份';
comment on column development_session.project_id is '创建时 Agent 项目，删除项目仍保留';
comment on column development_session.repository is '冻结仓库地址';
comment on column development_session.branch_name is '冻结 Git 短分支名';
comment on column development_session.baseline_sha is '创建时远端完整 SHA';
comment on column development_session.config_snapshot is '绑定时配置快照 JSON';
comment on column development_session.storage_key is '受控临时 clone 的服务端随机键';
comment on column development_session.revision is '本地编辑修订号';
comment on column development_session.version is 'CAS 状态版本';
comment on column development_session.local_state is '本地源码状态，不表示 Git 保存或部署';
comment on column development_session.local_digest is '最近已核实的完整本地文件摘要';
comment on column development_session.pushed_sha is '最近已核实推送的 SHA';
comment on column development_session.selected_build_id is '显式选定的分析构建，空时首次解析部署';
create table development_operation (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 operation_id text not null unique,
 session_id text not null,
 project_id text not null,
 type text not null,
 idempotency_key text not null,
 request_digest text not null,
 request_json text not null,
 context_json text not null,
 phase text not null,
 status text not null,
 version bigint not null,
 candidate_sha text,
 candidate_parent_sha text,
 candidate_storage_path text,
 pushed_sha text,
 merge_sha text,
 build_id text,
 attempt_number integer not null,
 error_code text,
 last_checked_at timestamp ,
 unique(session_id,type,idempotency_key)
);
comment on table development_operation is '开发上下文与动作持久事实，保留历史';
comment on column development_operation.id is '数据库内部主键';
comment on column development_operation.tenant_id is '固定运行租户';
comment on column development_operation.created_at is '创建时间';
comment on column development_operation.updated_at is '最近状态更新时间';
comment on column development_operation.deleted_at is '审计删除时间';
comment on column development_operation.is_deleted is '逻辑删除标志，历史操作不自动删除';
comment on column development_operation.operation_id is '持久动作 UUID';
comment on column development_operation.session_id is '动作归属会话；分支创建时使用项目操作域';
comment on column development_operation.project_id is 'Agent 项目归属';
comment on column development_operation.type is 'SAVE/PREVIEW/DELIVER/CREATE_BRANCH';
comment on column development_operation.idempotency_key is '调用方动作重试键';
comment on column development_operation.request_digest is '不可变完整请求摘要';
comment on column development_operation.request_json is '动作确认输入，不保存文件正文';
comment on column development_operation.context_json is '受理时仓库分支及配置快照';
comment on column development_operation.phase is '持久阶段，先记录后外部 I/O';
comment on column development_operation.status is 'PENDING/RUNNING/SUCCEEDED/BLOCKED/RESULT_UNKNOWN/FAILED';
comment on column development_operation.version is '操作恢复及状态推进 CAS 版本';
comment on column development_operation.candidate_sha is '外部写入前已固定的候选提交';
comment on column development_operation.candidate_parent_sha is '保存候选的远端父提交，恢复时不得改变';
comment on column development_operation.candidate_storage_path is '尚未核实推送的合并候选服务端目录';
comment on column development_operation.pushed_sha is '核实后的保存提交';
comment on column development_operation.merge_sha is '核实后的实际合并提交 M';
comment on column development_operation.build_id is '当前构建尝试身份';
comment on column development_operation.attempt_number is '构建显式重试序号，派生稳定幂等键';
comment on column development_operation.error_code is '脱敏失败或阻塞原因';
comment on column development_operation.last_checked_at is '公平恢复调度的最近扫描时间';
create table development_build_attempt (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 operation_id text not null,
 attempt_number integer not null,
 build_id text not null,
 commit_sha text not null,
 environment text not null,
 idempotency_key text not null ,
 unique(operation_id,attempt_number),
 unique(build_id)
);
comment on table development_build_attempt is '开发上下文与动作持久事实，保留历史';
comment on column development_build_attempt.id is '数据库内部主键';
comment on column development_build_attempt.tenant_id is '固定运行租户';
comment on column development_build_attempt.created_at is '创建时间';
comment on column development_build_attempt.updated_at is '最近状态更新时间';
comment on column development_build_attempt.deleted_at is '审计删除时间';
comment on column development_build_attempt.is_deleted is '逻辑删除标志，历史操作不自动删除';
comment on column development_build_attempt.operation_id is '所属持久动作';
comment on column development_build_attempt.attempt_number is '动作内构建尝试序号';
comment on column development_build_attempt.build_id is 'dbt-service 构建 UUID';
comment on column development_build_attempt.commit_sha is '该尝试固定源码 SHA';
comment on column development_build_attempt.environment is 'PREVIEW 或 PRODUCTION';
comment on column development_build_attempt.idempotency_key is '原请求重试键';
create index development_pending on development_operation(status,last_checked_at,created_at);
create index development_project_sessions on development_session(project_id);
create table development_recovery (
 id bigserial primary key,
 tenant_id text not null default 'tenant_local',
 created_at timestamp not null default localtimestamp,
 updated_at timestamp not null default localtimestamp,
 deleted_at timestamp,
 is_deleted boolean not null default false,
 operation_id text not null,
 idempotency_key text not null,
 expected_version bigint not null,
 unique(operation_id,idempotency_key)
);
comment on table development_recovery is '显式恢复请求回执，避免重试重复推进';
comment on column development_recovery.id is '数据库内部主键';
comment on column development_recovery.tenant_id is '固定运行租户';
comment on column development_recovery.created_at is '受理时间';
comment on column development_recovery.updated_at is '回执更新时间';
comment on column development_recovery.deleted_at is '审计删除时间';
comment on column development_recovery.is_deleted is '逻辑删除标志';
comment on column development_recovery.operation_id is '所属持久操作';
comment on column development_recovery.idempotency_key is '恢复请求幂等键';
comment on column development_recovery.expected_version is '恢复请求确认的操作版本';
