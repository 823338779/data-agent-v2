create table conversation_project (
    id bigint generated always as identity primary key,
    project_id varchar(64) not null,
    tenant_id varchar(64) not null,
    name varchar(60) not null,
    version bigint not null default 0,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    constraint uq_conversation_project_tenant_id unique (tenant_id, project_id),
    constraint fk_conversation_project_tenant foreign key (tenant_id) references tenant(tenant_id),
    constraint ck_conversation_project_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

alter table conversation_session add column project_id varchar(64);
alter table conversation_session add column pinned_at timestamptz;
alter table conversation_session add constraint fk_conversation_session_project
    foreign key (tenant_id, project_id) references conversation_project(tenant_id, project_id);
create index idx_conversation_session_project on conversation_session(tenant_id, project_id)
    where is_deleted = false;

comment on table conversation_project is '会话侧栏的一级项目分组，不共享会话内容。';
comment on column conversation_project.id is '数据库自增主键，仅供持久化内部定位。';
comment on column conversation_project.project_id is '项目业务唯一标识，名称变化不影响关联。';
comment on column conversation_project.tenant_id is '沿用当前固定租户标识。';
comment on column conversation_project.name is '用户可见项目名，允许同名，最多60个UTF-16代码单元。';
comment on column conversation_project.version is '重命名和删除项目使用的乐观锁版本。';
comment on column conversation_project.created_at is '项目创建时的数据库本地时间。';
comment on column conversation_project.updated_at is '项目最后更新时的数据库本地时间。';
comment on column conversation_project.deleted_at is '逻辑删除时间，未删除时为空。';
comment on column conversation_project.is_deleted is '逻辑删除标记，与deleted_at保持一致。';
comment on column conversation_session.project_id is '所属项目业务标识，空表示未分组；不影响上下文。';
comment on column conversation_session.pinned_at is '置顶时间，空表示未置顶；重复置顶不刷新。';
