-- 扩展不可变消息日志：公开投影只读取 user visibility，ReAct 内部消息保留完整工具边界。
alter table agent_message drop constraint ck_agent_message_role;
alter table agent_message
    add column tool_calls jsonb,
    add column tool_execution_id varchar(64),
    add column tool_call_id varchar(256),
    add column tool_name varchar(256),
    add column visibility varchar(16) not null default 'user',
    add constraint ck_agent_message_role
        check (role in ('user', 'assistant', 'system', 'tool')),
    add constraint ck_agent_message_visibility
        check (visibility in ('user', 'internal')),
    add constraint ck_agent_message_tool_shape check (
        (
            role = 'tool'
            and visibility = 'internal'
            and tool_calls is null
            and tool_execution_id is not null
            and tool_call_id is not null
            and tool_name is not null
        )
        or (
            role <> 'tool'
            and tool_execution_id is null
            and tool_call_id is null
            and tool_name is null
        )
    ),
    add constraint ck_agent_message_tool_calls_shape check (
        tool_calls is null
        or (
            role = 'assistant'
            and visibility = 'internal'
            and jsonb_typeof(tool_calls) = 'object'
            and tool_calls @> '{"version": 1}'::jsonb
            and jsonb_typeof(tool_calls -> 'calls') = 'array'
        )
    );

-- Model Turn 仅保存恢复所需边界和可过期 reasoning，不复制 prompt、回答或工具正文。
create table agent_model_turn (
    id bigint generated always as identity primary key,
    model_turn_id varchar(64) not null,
    tenant_id varchar(64) not null,
    run_id varchar(64) not null,
    model_turn_sequence bigint not null,
    execution_attempt bigint not null,
    input_up_to_message_sequence bigint not null,
    status varchar(16) not null,
    reasoning text,
    reasoning_expires_at timestamptz,
    started_at timestamptz not null default current_timestamp,
    completed_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_model_turn_business unique (model_turn_id),
    constraint uq_agent_model_turn_boundary
        unique (tenant_id, run_id, model_turn_sequence),
    constraint fk_agent_model_turn_run foreign key (tenant_id, run_id)
        references agent_run(tenant_id, run_id),
    constraint ck_agent_model_turn_sequence check (model_turn_sequence >= 1),
    constraint ck_agent_model_turn_execution_attempt check (execution_attempt >= 1),
    constraint ck_agent_model_turn_input_boundary check (input_up_to_message_sequence >= 0),
    constraint ck_agent_model_turn_status
        check (status in ('streaming', 'completed', 'abandoned', 'failed')),
    constraint ck_agent_model_turn_completion check (
        (status = 'streaming' and completed_at is null)
        or (status <> 'streaming' and completed_at is not null)
    ),
    constraint ck_agent_model_turn_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create index ix_agent_model_turn_recovery
    on agent_model_turn (tenant_id, run_id, status, model_turn_sequence);

-- 每次工具调用独立持久化；参数仍以 assistant tool_calls 消息为唯一事实来源。
create table agent_tool_execution (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    tool_execution_id varchar(64) not null,
    run_id varchar(64) not null,
    model_turn_sequence bigint not null,
    execution_attempt bigint not null default 1,
    assistant_message_id varchar(64) not null,
    tool_call_id varchar(256) not null,
    tool_name varchar(256) not null,
    status varchar(16) not null,
    execution_count integer not null default 0,
    idempotency_key varchar(256),
    observation_message_id varchar(64),
    failure_code varchar(64),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_tool_execution_id unique (tool_execution_id),
    constraint uq_agent_tool_execution_business
        unique (tenant_id, tool_execution_id),
    constraint uq_agent_tool_execution_turn_call
        unique (tenant_id, run_id, model_turn_sequence, tool_call_id),
    constraint fk_agent_tool_execution_turn
        foreign key (tenant_id, run_id, model_turn_sequence)
        references agent_model_turn(tenant_id, run_id, model_turn_sequence),
    constraint fk_agent_tool_execution_assistant_message
        foreign key (tenant_id, assistant_message_id)
        references agent_message(tenant_id, message_id),
    constraint ck_agent_tool_execution_status
        check (status in ('prepared', 'executing', 'succeeded', 'failed')),
    constraint ck_agent_tool_execution_count check (execution_count >= 0),
    constraint ck_agent_tool_execution_terminal_observation check (
        (status in ('prepared', 'executing') and observation_message_id is null and completed_at is null)
        or (status in ('succeeded', 'failed') and observation_message_id is not null
            and completed_at is not null)
    ),
    constraint ck_agent_tool_execution_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create index ix_agent_tool_execution_recovery
    on agent_tool_execution (tenant_id, run_id, status, model_turn_sequence, id);

-- 以双向复合外键把唯一 Observation 锁定到同一 execution，避免终态引用其他工具消息。
alter table agent_message
    add constraint uq_agent_message_tool_observation
        unique (tenant_id, message_id, tool_execution_id),
    add constraint fk_agent_message_tool_execution
        foreign key (tenant_id, tool_execution_id)
        references agent_tool_execution(tenant_id, tool_execution_id);

alter table agent_tool_execution
    add constraint fk_agent_tool_execution_observation
        foreign key (tenant_id, observation_message_id, tool_execution_id)
        references agent_message(tenant_id, message_id, tool_execution_id);
