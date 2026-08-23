create table conversation_context_lsm_segment (
    id bigint generated always as identity primary key,
    summary_segment_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    segment_key char(64) not null,
    kind varchar(16) not null check (kind in ('STANDARD', 'ROOT')),
    level integer not null check (level >= 0),
    start_turn_sequence bigint not null,
    end_turn_sequence bigint not null,
    start_message_sequence bigint not null,
    end_message_sequence bigint not null,
    partial_segment boolean not null,
    summary_document jsonb not null,
    estimated_token_count integer not null check (estimated_token_count >= 0),
    source_segment_ids jsonb not null,
    source_content_sha256 char(64) not null,
    summary_schema_version integer not null,
    prompt_version integer not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    creation_policy jsonb not null,
    source_segment_count integer not null check (source_segment_count >= 0),
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_segment_key unique (tenant_id, conversation_session_id, segment_key),
    constraint ck_context_lsm_segment_range check (
        start_turn_sequence <= end_turn_sequence
        and start_message_sequence <= end_message_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_lsm_manifest (
    id bigint generated always as identity primary key,
    context_manifest_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    active_root_segment_id varchar(64),
    active_segment_ids jsonb not null default '[]'::jsonb,
    pending_raw_start_turn_sequence bigint,
    pending_raw_end_turn_sequence bigint,
    hot_from_turn_sequence bigint not null default 0,
    evaluated_up_to_message_sequence bigint not null default 0,
    effective_policy_version integer not null,
    effective_fanout integer not null,
    effective_max_level integer not null,
    generation bigint not null default 0 check (generation >= 0),
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_manifest_session unique (tenant_id, conversation_session_id),
    constraint ck_context_lsm_manifest_boundaries check (
        hot_from_turn_sequence >= 0 and evaluated_up_to_message_sequence >= 0),
    constraint ck_context_lsm_manifest_policy check (
        effective_policy_version > 0 and effective_fanout between 3 and 10
        and effective_max_level between 1 and 8),
    constraint ck_context_lsm_manifest_raw_range check (
        (pending_raw_start_turn_sequence is null and pending_raw_end_turn_sequence is null)
        or pending_raw_start_turn_sequence <= pending_raw_end_turn_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

comment on table conversation_context_lsm_segment is 'LSM 上下文不可变摘要段。';
comment on column conversation_context_lsm_segment.id is '数据库代理主键。';
comment on column conversation_context_lsm_segment.summary_segment_id is '摘要段业务唯一标识。';
comment on column conversation_context_lsm_segment.tenant_id is '摘要段所属租户。';
comment on column conversation_context_lsm_segment.conversation_session_id is '摘要段所属会话。';
comment on column conversation_context_lsm_segment.segment_key is '由摘要输入确定性计算的段幂等键。';
comment on column conversation_context_lsm_segment.kind is '摘要段类型。';
comment on column conversation_context_lsm_segment.level is '摘要段所在层级。';
comment on column conversation_context_lsm_segment.start_turn_sequence is '覆盖的首轮序号。';
comment on column conversation_context_lsm_segment.end_turn_sequence is '覆盖的末轮序号。';
comment on column conversation_context_lsm_segment.start_message_sequence is '覆盖的首消息序号。';
comment on column conversation_context_lsm_segment.end_message_sequence is '覆盖的末消息序号。';
comment on column conversation_context_lsm_segment.partial_segment is '是否包含未完成轮次。';
comment on column conversation_context_lsm_segment.summary_document is '校验后的摘要 JSON。';
comment on column conversation_context_lsm_segment.estimated_token_count is '摘要估算 token 数。';
comment on column conversation_context_lsm_segment.source_segment_ids is '来源摘要段标识数组。';
comment on column conversation_context_lsm_segment.source_content_sha256 is '摘要来源内容指纹。';
comment on column conversation_context_lsm_segment.summary_schema_version is '摘要 schema 版本。';
comment on column conversation_context_lsm_segment.prompt_version is '摘要提示词版本。';
comment on column conversation_context_lsm_segment.model_provider is '摘要模型提供商。';
comment on column conversation_context_lsm_segment.model_name is '摘要模型名称。';
comment on column conversation_context_lsm_segment.creation_policy is '生成摘要时使用的策略快照。';
comment on column conversation_context_lsm_segment.source_segment_count is '来源摘要段数量。';
comment on column conversation_context_lsm_segment.created_at is '创建时间。';
comment on column conversation_context_lsm_segment.created_by is '创建者。';
comment on column conversation_context_lsm_segment.updated_at is '更新时间。';
comment on column conversation_context_lsm_segment.updated_by is '更新者。';
comment on column conversation_context_lsm_segment.deleted is '逻辑删除标记。';
comment on column conversation_context_lsm_segment.version is '乐观锁版本。';

comment on table conversation_context_lsm_manifest is '会话当前发布的 LSM 上下文视图。';
comment on column conversation_context_lsm_manifest.id is '数据库代理主键。';
comment on column conversation_context_lsm_manifest.context_manifest_id is 'manifest 业务唯一标识。';
comment on column conversation_context_lsm_manifest.tenant_id is 'manifest 所属租户。';
comment on column conversation_context_lsm_manifest.conversation_session_id is 'manifest 所属会话。';
comment on column conversation_context_lsm_manifest.active_root_segment_id is '当前 ROOT 摘要段标识。';
comment on column conversation_context_lsm_manifest.active_segment_ids is '活动摘要段标识数组。';
comment on column conversation_context_lsm_manifest.pending_raw_start_turn_sequence is '待压缩原始历史首轮。';
comment on column conversation_context_lsm_manifest.pending_raw_end_turn_sequence is '待压缩原始历史末轮。';
comment on column conversation_context_lsm_manifest.hot_from_turn_sequence is 'Hot 区间起始轮次。';
comment on column conversation_context_lsm_manifest.evaluated_up_to_message_sequence is '已评估最高消息序号。';
comment on column conversation_context_lsm_manifest.effective_policy_version is '当前策略版本。';
comment on column conversation_context_lsm_manifest.effective_fanout is '当前合并扇出。';
comment on column conversation_context_lsm_manifest.effective_max_level is '当前最大层级。';
comment on column conversation_context_lsm_manifest.generation is '每次发布单调递增的 manifest 代次。';
comment on column conversation_context_lsm_manifest.created_at is '创建时间。';
comment on column conversation_context_lsm_manifest.created_by is '创建者。';
comment on column conversation_context_lsm_manifest.updated_at is '更新时间。';
comment on column conversation_context_lsm_manifest.updated_by is '更新者。';
comment on column conversation_context_lsm_manifest.deleted is '逻辑删除标记。';
comment on column conversation_context_lsm_manifest.version is 'CAS 乐观锁版本。';
