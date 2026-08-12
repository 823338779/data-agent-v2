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
    warm_cutoff_turn_sequence bigint not null default 0,
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
    constraint ck_context_lsm_manifest_raw_range check (
        (pending_raw_start_turn_sequence is null and pending_raw_end_turn_sequence is null)
        or pending_raw_start_turn_sequence <= pending_raw_end_turn_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_lsm_job (
    id bigint generated always as identity primary key,
    compaction_job_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    execution_attempt bigint not null check (execution_attempt > 0),
    input_up_to_message_sequence bigint not null check (input_up_to_message_sequence >= 0),
    source_manifest_generation bigint not null check (source_manifest_generation >= 0),
    source_manifest_version bigint not null check (source_manifest_version >= 0),
    policy_snapshot jsonb not null,
    plan_document jsonb not null,
    plan_sha256 char(64) not null,
    status varchar(16) not null check (
        status in ('PLANNED', 'GENERATING', 'READY', 'PUBLISHED', 'SUPERSEDED', 'CANCELLED', 'FAILED')),
    owner_instance_id varchar(128) not null,
    failure_code varchar(64),
    completed_at timestamp,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_job_boundary unique (
        tenant_id, execution_id, execution_attempt, input_up_to_message_sequence),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_lsm_execution_policy (
    id bigint generated always as identity primary key,
    execution_policy_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    policy_snapshot jsonb not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0,
    constraint uk_context_lsm_execution_policy unique (tenant_id, execution_id),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

comment on table conversation_context_lsm_segment is 'LSM 上下文不可变摘要段。';
comment on table conversation_context_lsm_manifest is '会话当前发布的 LSM 上下文视图。';
comment on table conversation_context_lsm_job is '可恢复的 LSM 上下文压缩作业。';
comment on table conversation_context_lsm_execution_policy is '执行首次使用时冻结的 LSM 上下文策略。';

do $$
declare
    item record;
begin
    -- 为插件表每个字段写入数据库级说明，便于迁移后直接审计字段用途。
    for item in
        select c.relname as table_name, a.attname as column_name
          from pg_attribute a
          join pg_class c on c.oid = a.attrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public'
           and c.relname in (
               'conversation_context_lsm_segment',
               'conversation_context_lsm_manifest',
               'conversation_context_lsm_job',
               'conversation_context_lsm_execution_policy')
           and a.attnum > 0 and not a.attisdropped
    loop
        execute format(
            'comment on column %I.%I is %L',
            item.table_name,
            item.column_name,
            'LSM 上下文持久化字段，具体语义由字段名和插件持久化实体定义。');
    end loop;
end $$;
