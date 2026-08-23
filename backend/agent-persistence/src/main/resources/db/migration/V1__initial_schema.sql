create table tenant (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    name varchar(128) not null,
    status varchar(32) not null default 'active',
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_tenant_tenant_id unique (tenant_id),
    constraint ck_tenant_status check (status in ('active', 'disabled')),
    constraint ck_tenant_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table user_account (
    id bigint generated always as identity primary key,
    user_id varchar(64) not null,
    tenant_id varchar(64) not null,
    username varchar(128) not null,
    password_hash varchar(100) not null,
    status varchar(32) not null default 'active',
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_user_account_user_id unique (user_id),
    constraint uq_user_account_tenant_user_id unique (tenant_id, user_id),
    constraint uq_user_account_username unique (tenant_id, username),
    constraint fk_user_account_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint ck_user_account_status check (status in ('active', 'disabled')),
    constraint ck_user_account_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table tenant_membership (
    id bigint generated always as identity primary key,
    tenant_membership_id varchar(64) not null,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    role varchar(32) not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_tenant_membership_membership_id unique (tenant_membership_id),
    constraint uq_tenant_membership_tenant_membership_id
        unique (tenant_id, tenant_membership_id),
    constraint uq_tenant_membership_role unique (tenant_id, user_id, role),
    constraint fk_tenant_membership_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_tenant_membership_user foreign key (tenant_id, user_id)
        references user_account(tenant_id, user_id),
    constraint ck_tenant_membership_role check (role in ('ADMIN', 'USER')),
    constraint ck_tenant_membership_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table conversation_session (
    id bigint generated always as identity primary key,
    conversation_session_id varchar(64) not null,
    tenant_id varchar(64) not null,
    title varchar(256) not null,
    tag varchar(128),
    conversation_message_sequence bigint not null default 0,
    execution_event_sequence bigint not null default 0,
    last_activity_at timestamptz not null default current_timestamp,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_conversation_session_tenant_conversation_session_id
        unique (tenant_id, conversation_session_id),
    constraint fk_conversation_session_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint ck_conversation_session_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table conversation_message (
    id bigint generated always as identity primary key,
    conversation_message_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64),
    role varchar(16) not null,
    content text not null,
    thinking_process jsonb not null default '[]'::jsonb,
    conversation_message_sequence bigint not null,
    author_user_id varchar(64),
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_conversation_message_conversation_message_id
        unique (conversation_message_id),
    constraint uq_conversation_message_tenant_conversation_message_id
        unique (tenant_id, conversation_message_id),
    constraint uq_conversation_conversation_message_sequence
        unique (tenant_id, conversation_session_id, conversation_message_sequence),
    constraint fk_conversation_message_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_conversation_message_session foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id),
    constraint fk_conversation_message_author foreign key (tenant_id, author_user_id)
        references user_account(tenant_id, user_id),
    constraint ck_conversation_message_role check (role in ('user', 'assistant', 'system')),
    constraint ck_conversation_message_thinking_process_array
        check (jsonb_typeof(thinking_process) = 'array'),
    constraint ck_conversation_message_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table agent_execution (
    id bigint generated always as identity primary key,
    execution_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    triggering_conversation_message_id varchar(64) not null,
    status varchar(32) not null,
    request_type varchar(32) not null,
    request_idempotency_key varchar(256) not null,
    request_payload_hash varchar(64) not null,
    failure_code varchar(64),
    failure_detail text,
    hard_deadline_at timestamptz not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_execution_execution_id unique (execution_id),
    constraint uq_agent_execution_tenant_execution_id unique (tenant_id, execution_id),
    constraint uq_agent_execution_request_idempotency unique (
        tenant_id, conversation_session_id, request_type, request_idempotency_key),
    constraint fk_agent_execution_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_agent_execution_session foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id),
    constraint fk_agent_execution_triggering_message
        foreign key (tenant_id, triggering_conversation_message_id)
        references conversation_message(tenant_id, conversation_message_id),
    constraint ck_agent_execution_status
        check (status in ('running', 'done', 'cancelled', 'failed')),
    constraint ck_agent_execution_request_type
        check (request_type in ('message_submission', 'execution_retry')),
    constraint ck_agent_execution_request_payload_hash
        check (request_payload_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_agent_execution_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

alter table conversation_message
    add constraint fk_conversation_message_execution
    foreign key (tenant_id, execution_id)
    references agent_execution(tenant_id, execution_id);

create table agent_execution_event (
    id bigint generated always as identity primary key,
    execution_event_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    execution_event_sequence bigint not null,
    type varchar(64) not null,
    occurred_at timestamptz not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_execution_event_execution_event_id unique (execution_event_id),
    constraint uq_agent_execution_event_tenant_execution_event_id
        unique (tenant_id, execution_event_id),
    constraint uq_agent_execution_event_session_sequence
        unique (tenant_id, conversation_session_id, execution_event_sequence),
    constraint uq_agent_execution_execution_event_sequence
        unique (tenant_id, execution_id, execution_event_sequence),
    constraint fk_agent_execution_event_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_agent_execution_event_session foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id),
    constraint fk_agent_execution_event_execution foreign key (tenant_id, execution_id)
        references agent_execution(tenant_id, execution_id),
    constraint ck_agent_execution_event_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table agent_execution_stream_checkpoint (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    execution_id varchar(64) not null,
    model_invocation_sequence bigint not null default 0,
    phase varchar(32) not null,
    output_delta_sequence bigint not null default 0,
    answer text not null default '',
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_execution_stream_checkpoint_tenant_execution
        unique (tenant_id, execution_id),
    constraint fk_agent_execution_stream_checkpoint_execution foreign key (tenant_id, execution_id)
        references agent_execution(tenant_id, execution_id),
    constraint ck_agent_execution_stream_checkpoint_model_sequence
        check (model_invocation_sequence >= 0),
    constraint ck_agent_execution_stream_checkpoint_delta_sequence
        check (output_delta_sequence >= 0),
    constraint ck_agent_execution_stream_checkpoint_phase check (
        phase in (
            'RECEIVED',
            'PREPARING_CONTEXT',
            'CONNECTING_MODEL',
            'ANSWERING',
            'PERSISTING',
            'COMPLETED')),
    constraint ck_agent_execution_stream_checkpoint_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create unique index uq_agent_execution_one_active
    on agent_execution (tenant_id, conversation_session_id)
    where is_deleted = false and status = 'running';

create unique index uq_conversation_message_execution_assistant
    on conversation_message (tenant_id, execution_id)
    where role = 'assistant' and is_deleted = false;

create index ix_conversation_message_session_sequence
    on conversation_message (tenant_id, conversation_session_id, conversation_message_sequence)
    where is_deleted = false;

create index ix_conversation_session_tenant_updated
    on conversation_session (tenant_id, updated_at desc)
    where is_deleted = false;

create index ix_agent_execution_event_session_sequence
    on agent_execution_event (tenant_id, conversation_session_id, execution_event_sequence)
    where is_deleted = false;

comment on table tenant is '业务数据隔离边界；当前运行时使用固定租户，保留该表用于统一数据归属。';
comment on column tenant.id is '数据库代理主键，不对外暴露。';
comment on column tenant.tenant_id is '租户业务唯一标识，用于所有业务表的数据边界。';
comment on column tenant.name is '租户显示名称。';
comment on column tenant.status is '租户状态：active 可用，disabled 停用。';
comment on column tenant.created_at is '记录创建时的数据库本地时间。';
comment on column tenant.updated_at is '记录最后更新时的数据库本地时间。';
comment on column tenant.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column tenant.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column tenant.version is 'MyBatis-Plus 乐观锁版本。';

comment on table user_account is '租户内用户账号；当前阶段仅承载固定运行身份。';
comment on column user_account.id is '数据库代理主键，不对外暴露。';
comment on column user_account.user_id is '用户业务唯一标识。';
comment on column user_account.tenant_id is '用户所属租户业务标识。';
comment on column user_account.username is '租户内唯一的登录名或显示账号名。';
comment on column user_account.password_hash is '密码哈希占位字段，不得存储明文密码。';
comment on column user_account.status is '账号状态：active 可用，disabled 停用。';
comment on column user_account.created_at is '记录创建时的数据库本地时间。';
comment on column user_account.updated_at is '记录最后更新时的数据库本地时间。';
comment on column user_account.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column user_account.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column user_account.version is 'MyBatis-Plus 乐观锁版本。';

comment on table tenant_membership is '用户在租户中的角色关系。';
comment on column tenant_membership.id is '数据库代理主键，不对外暴露。';
comment on column tenant_membership.tenant_membership_id is '成员关系业务唯一标识。';
comment on column tenant_membership.tenant_id is '成员关系所属租户。';
comment on column tenant_membership.user_id is '成员关系指向的用户业务标识。';
comment on column tenant_membership.role is '租户角色：ADMIN 管理员，USER 普通用户。';
comment on column tenant_membership.created_at is '记录创建时的数据库本地时间。';
comment on column tenant_membership.updated_at is '记录最后更新时的数据库本地时间。';
comment on column tenant_membership.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column tenant_membership.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column tenant_membership.version is 'MyBatis-Plus 乐观锁版本。';

comment on table conversation_session is '对话会话聚合根，维护会话级单调序列和最近活动时间。';
comment on column conversation_session.id is '数据库代理主键，不对外暴露。';
comment on column conversation_session.conversation_session_id is '会话业务唯一标识。';
comment on column conversation_session.tenant_id is '会话所属租户。';
comment on column conversation_session.title is '会话标题。';
comment on column conversation_session.tag is '用户可选的会话分类标签；未设置时为空。';
comment on column conversation_session.conversation_message_sequence is '会话内已分配的最高消息序号，从 1 单调递增。';
comment on column conversation_session.execution_event_sequence is '会话内已分配的最高执行事件序号，从 1 单调递增。';
comment on column conversation_session.last_activity_at is '最后一次用户或执行活动时间，用于会话排序。';
comment on column conversation_session.created_at is '记录创建时的数据库本地时间。';
comment on column conversation_session.updated_at is '记录最后更新时的数据库本地时间。';
comment on column conversation_session.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column conversation_session.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column conversation_session.version is '聚合更新使用的乐观锁版本。';

comment on table conversation_message is '会话消息正文及其可展示的思考过程。';
comment on column conversation_message.id is '数据库代理主键，不对外暴露。';
comment on column conversation_message.conversation_message_id is '消息业务唯一标识。';
comment on column conversation_message.tenant_id is '消息所属租户。';
comment on column conversation_message.conversation_session_id is '消息所属会话。';
comment on column conversation_message.execution_id is '生成该消息的执行标识；用户或系统直接写入的消息可为空。';
comment on column conversation_message.role is '消息角色：user、assistant 或 system。';
comment on column conversation_message.content is '面向模型和用户的消息正文。';
comment on column conversation_message.thinking_process is '结构化思考过程数组；不存储模型私有推理。';
comment on column conversation_message.conversation_message_sequence is '消息在会话内的稳定顺序，从 1 单调递增。';
comment on column conversation_message.author_user_id is '用户消息的作者标识；assistant 或 system 消息可为空。';
comment on column conversation_message.created_at is '记录创建时的数据库本地时间。';
comment on column conversation_message.updated_at is '记录最后更新时的数据库本地时间。';
comment on column conversation_message.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column conversation_message.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column conversation_message.version is '消息更新使用的乐观锁版本。';

comment on table agent_execution is '一次本地 Agent 执行的持久化事实，不包含队列、租约或 Worker 所有权。';
comment on column agent_execution.id is '数据库代理主键，不对外暴露。';
comment on column agent_execution.execution_id is '执行业务唯一标识，也是本机任务注册和取消键。';
comment on column agent_execution.tenant_id is '执行所属租户。';
comment on column agent_execution.conversation_session_id is '执行所属会话。';
comment on column agent_execution.triggering_conversation_message_id is '触发本次执行的用户消息标识。';
comment on column agent_execution.status is '执行状态：running、done、cancelled 或 failed。';
comment on column agent_execution.request_type is '幂等请求类型：消息提交或执行重试。';
comment on column agent_execution.request_idempotency_key is '调用方提供的幂等键，在同会话和请求类型内唯一。';
comment on column agent_execution.request_payload_hash is '规范化请求负载的 SHA-256，用于识别幂等键冲突。';
comment on column agent_execution.failure_code is '失败分类码；非 failed 状态通常为空。';
comment on column agent_execution.failure_detail is '供诊断使用的失败摘要，不应包含凭据或完整敏感输入。';
comment on column agent_execution.hard_deadline_at is '执行硬截止时间；超过后禁止继续模型、工具或压缩副作用。';
comment on column agent_execution.created_at is '记录创建时的数据库本地时间。';
comment on column agent_execution.updated_at is '记录最后更新时的数据库本地时间。';
comment on column agent_execution.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column agent_execution.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column agent_execution.version is '执行状态迁移使用的乐观锁版本。';

comment on table agent_execution_event is '执行生命周期事件流，用于 SSE 重放和会话状态投影。';
comment on column agent_execution_event.id is '数据库代理主键，不对外暴露。';
comment on column agent_execution_event.execution_event_id is '执行事件业务唯一标识。';
comment on column agent_execution_event.tenant_id is '事件所属租户。';
comment on column agent_execution_event.conversation_session_id is '事件所属会话。';
comment on column agent_execution_event.execution_id is '事件所属执行。';
comment on column agent_execution_event.execution_event_sequence is '事件在会话和执行内的单调序号，用作 SSE 游标。';
comment on column agent_execution_event.type is '执行事件类型，例如启动、阶段变化、完成或失败。';
comment on column agent_execution_event.occurred_at is '业务事件实际发生时间，使用带时区时间。';
comment on column agent_execution_event.created_at is '记录创建时的数据库本地时间。';
comment on column agent_execution_event.updated_at is '记录最后更新时的数据库本地时间。';
comment on column agent_execution_event.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column agent_execution_event.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column agent_execution_event.version is '事件记录更新使用的乐观锁版本。';

comment on table agent_execution_stream_checkpoint is '本地流式回答的最新可恢复快照，每个执行最多一条。';
comment on column agent_execution_stream_checkpoint.id is '数据库代理主键，不对外暴露。';
comment on column agent_execution_stream_checkpoint.tenant_id is '流快照所属租户。';
comment on column agent_execution_stream_checkpoint.execution_id is '流快照所属执行，也是业务唯一键的一部分。';
comment on column agent_execution_stream_checkpoint.model_invocation_sequence is '本次执行内当前模型调用序号，0 表示尚未调用模型。';
comment on column agent_execution_stream_checkpoint.phase is '当前执行阶段，用于客户端展示和断线后恢复。';
comment on column agent_execution_stream_checkpoint.output_delta_sequence is '已持久化的最高输出增量序号，0 表示尚无增量。';
comment on column agent_execution_stream_checkpoint.answer is '截至当前增量拼接出的完整可见回答。';
comment on column agent_execution_stream_checkpoint.created_at is '记录创建时的数据库本地时间。';
comment on column agent_execution_stream_checkpoint.updated_at is '快照最后刷新时的数据库本地时间。';
comment on column agent_execution_stream_checkpoint.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column agent_execution_stream_checkpoint.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column agent_execution_stream_checkpoint.version is '快照更新使用的乐观锁版本。';
