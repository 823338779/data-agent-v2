create table conversation_context_hierarchical_cold (
    id bigint generated always as identity primary key,
    hierarchical_cold_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    start_turn_sequence bigint not null,
    end_turn_sequence bigint not null,
    start_message_sequence bigint not null,
    end_message_sequence bigint not null,
    summary_document jsonb not null,
    persisted_token_count integer not null,
    rendered_token_count integer not null,
    target_rendered_tokens integer not null,
    summary_schema_version integer not null,
    summary_prompt_version integer not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    source_content_sha256 char(64) not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_cold_identity
        unique (tenant_id, conversation_session_id, hierarchical_cold_id),
    constraint ck_hierarchical_cold_coverage check (
        start_turn_sequence > 0
        and end_turn_sequence >= start_turn_sequence
        and start_message_sequence > 0
        and end_message_sequence >= start_message_sequence
    ),
    constraint ck_hierarchical_cold_tokens check (
        persisted_token_count > 0
        and rendered_token_count > 0
        and target_rendered_tokens > 0
        and rendered_token_count <= target_rendered_tokens
    ),
    constraint ck_hierarchical_cold_versions check (
        summary_schema_version = 2 and summary_prompt_version = 2
    ),
    constraint fk_hierarchical_cold_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_hierarchical_checkpoint (
    id bigint generated always as identity primary key,
    hierarchical_checkpoint_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    active_cold_id varchar(64),
    evaluated_up_to_conversation_message_sequence bigint not null,
    strategy_generation bigint not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_checkpoint_session unique (tenant_id, conversation_session_id),
    constraint ck_hierarchical_checkpoint_non_negative check (
        evaluated_up_to_conversation_message_sequence >= 0 and strategy_generation >= 0
    ),
    constraint fk_hierarchical_checkpoint_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create index idx_hierarchical_cold_session_coverage
    on conversation_context_hierarchical_cold (
        tenant_id, conversation_session_id, end_turn_sequence desc
    ) where deleted = false;

comment on table conversation_context_hierarchical_cold is 'Hierarchical 策略生成的不可变滚动历史摘要。';
comment on column conversation_context_hierarchical_cold.id is '数据库代理主键，不对外暴露。';
comment on column conversation_context_hierarchical_cold.hierarchical_cold_id is '由摘要输入确定性生成的 Cold 业务标识。';
comment on column conversation_context_hierarchical_cold.tenant_id is 'Cold 所属租户。';
comment on column conversation_context_hierarchical_cold.conversation_session_id is 'Cold 所属会话。';
comment on column conversation_context_hierarchical_cold.start_turn_sequence is '摘要连续覆盖的首轮序号，最小为 1。';
comment on column conversation_context_hierarchical_cold.end_turn_sequence is '摘要连续覆盖的末轮序号，不小于首轮。';
comment on column conversation_context_hierarchical_cold.start_message_sequence is '摘要连续覆盖的首消息序号，最小为 1。';
comment on column conversation_context_hierarchical_cold.end_message_sequence is '摘要连续覆盖的末消息序号，不小于首消息。';
comment on column conversation_context_hierarchical_cold.summary_document is '通过 schema 校验的结构化滚动摘要 JSON。';
comment on column conversation_context_hierarchical_cold.persisted_token_count is '摘要文档持久化形态的估算 token 数。';
comment on column conversation_context_hierarchical_cold.rendered_token_count is '摘要渲染进模型上下文后的估算 token 数。';
comment on column conversation_context_hierarchical_cold.target_rendered_tokens is '生成时允许的摘要渲染 token 上限。';
comment on column conversation_context_hierarchical_cold.summary_schema_version is '摘要文档 schema 版本，当前固定为 2。';
comment on column conversation_context_hierarchical_cold.summary_prompt_version is '生成摘要的提示词版本，当前固定为 2。';
comment on column conversation_context_hierarchical_cold.model_provider is '摘要生成模型的提供商标识。';
comment on column conversation_context_hierarchical_cold.model_name is '摘要生成模型名称。';
comment on column conversation_context_hierarchical_cold.source_content_sha256 is '全部摘要来源内容的 SHA-256，用于幂等和审计。';
comment on column conversation_context_hierarchical_cold.created_at is '记录创建时间。';
comment on column conversation_context_hierarchical_cold.created_by is '记录创建者。';
comment on column conversation_context_hierarchical_cold.updated_at is '记录最后更新时间。';
comment on column conversation_context_hierarchical_cold.updated_by is '记录最后更新者。';
comment on column conversation_context_hierarchical_cold.deleted is '逻辑删除标记。';
comment on column conversation_context_hierarchical_cold.version is '乐观锁版本。';

comment on table conversation_context_hierarchical_checkpoint is '会话当前生效 Cold 的唯一发布指针。';
comment on column conversation_context_hierarchical_checkpoint.id is '数据库代理主键，不对外暴露。';
comment on column conversation_context_hierarchical_checkpoint.hierarchical_checkpoint_id is 'checkpoint 业务唯一标识。';
comment on column conversation_context_hierarchical_checkpoint.tenant_id is 'checkpoint 所属租户。';
comment on column conversation_context_hierarchical_checkpoint.conversation_session_id is 'checkpoint 所属会话，每个会话最多一条有效记录。';
comment on column conversation_context_hierarchical_checkpoint.active_cold_id is '当前发布的 Cold 标识；尚未压缩历史时为空。';
comment on column conversation_context_hierarchical_checkpoint.evaluated_up_to_conversation_message_sequence is '策略已评估到的最高消息序号。';
comment on column conversation_context_hierarchical_checkpoint.strategy_generation is '每次成功发布后单调递增的策略代次。';
comment on column conversation_context_hierarchical_checkpoint.created_at is '记录创建时间。';
comment on column conversation_context_hierarchical_checkpoint.created_by is '记录创建者。';
comment on column conversation_context_hierarchical_checkpoint.updated_at is '记录最后更新时间。';
comment on column conversation_context_hierarchical_checkpoint.updated_by is '记录最后更新者。';
comment on column conversation_context_hierarchical_checkpoint.deleted is '逻辑删除标记。';
comment on column conversation_context_hierarchical_checkpoint.version is 'checkpoint CAS 发布使用的乐观锁版本。';
