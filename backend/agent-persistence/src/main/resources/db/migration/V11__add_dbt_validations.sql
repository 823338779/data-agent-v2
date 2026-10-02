create table dbt_validation (
 id bigserial primary key, tenant_id text not null default 'tenant_local',
 record_id text not null unique, snapshot_key text not null unique,
 conversation_session_id text not null, workspace_id text not null, project_id text not null,
 project_subdir text not null, base_commit_sha text not null, tree_id text not null,
 draft_revision bigint not null, changes_digest text not null, attempt bigint not null default 0,
 state text not null, validation_id text, result_json text,
 created_at timestamp not null default localtimestamp, updated_at timestamp not null default localtimestamp,
 deleted_at timestamp, is_deleted boolean not null default false
);
comment on table dbt_validation is '固定草稿的 dbt 验证受理意图和已核对凭证，不保存 YAML';
comment on column dbt_validation.id is '内部自增主键';
comment on column dbt_validation.tenant_id is '固定运行租户';
comment on column dbt_validation.record_id is '本地记录业务身份，也是远端幂等键的一部分';
comment on column dbt_validation.snapshot_key is '会话工作区项目基线 tree 修订号的唯一摘要';
comment on column dbt_validation.conversation_session_id is '可信执行解析的会话，阻止跨会话读凭证';
comment on column dbt_validation.workspace_id is '冻结内容所属工作区';
comment on column dbt_validation.project_id is '固定服务项目';
comment on column dbt_validation.project_subdir is 'dbt 项目相对 Git 根目录';
comment on column dbt_validation.base_commit_sha is '冻结基线提交';
comment on column dbt_validation.tree_id is '已保活的完整冻结 tree';
comment on column dbt_validation.draft_revision is '单调草稿修订号';
comment on column dbt_validation.changes_digest is '跨语言 changes-v1 摘要';
comment on column dbt_validation.attempt is '仅明确失败后递增的尝试序号';
comment on column dbt_validation.state is 'UNKNOWN 表示回执未知，其余为服务任务状态';
comment on column dbt_validation.validation_id is '服务验证任务身份，回执未知时为空';
comment on column dbt_validation.result_json is '已核对绑定字段的有界响应，空值不能授权候选';
comment on column dbt_validation.created_at is '创建时间';
comment on column dbt_validation.updated_at is '最后更新时间';
comment on column dbt_validation.deleted_at is '逻辑删除时间，未删除为空';
comment on column dbt_validation.is_deleted is '逻辑删除标记';
