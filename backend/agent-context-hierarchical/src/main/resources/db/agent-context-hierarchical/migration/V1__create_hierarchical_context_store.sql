create table conversation_context_hierarchical_execution_policy (
    id bigint generated always as identity primary key,
    hierarchical_execution_policy_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    policy_document jsonb not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_execution_policy unique (tenant_id, execution_id),
    constraint fk_hierarchical_execution_policy_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_hierarchical_checkpoint (
    id bigint generated always as identity primary key,
    hierarchical_checkpoint_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    cold_summary_document jsonb not null,
    summarized_up_to_conversation_message_sequence bigint not null,
    summarized_completed_turn_count bigint not null,
    estimated_token_count integer not null,
    summary_schema_version integer not null,
    summary_prompt_version integer not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    active_frontier_document jsonb not null,
    strategy_generation bigint not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_checkpoint_session
        unique (tenant_id, conversation_session_id),
    constraint ck_hierarchical_checkpoint_non_negative check (
        summarized_up_to_conversation_message_sequence >= 0
        and summarized_completed_turn_count >= 0
        and estimated_token_count >= 0
        and strategy_generation >= 0),
    constraint ck_hierarchical_checkpoint_versions check (
        summary_schema_version > 0 and summary_prompt_version > 0),
    constraint fk_hierarchical_checkpoint_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_hierarchical_summary_node (
    id bigint generated always as identity primary key,
    hierarchical_summary_node_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    level integer not null,
    covered_completed_turn_count bigint not null,
    start_conversation_message_sequence bigint not null,
    end_conversation_message_sequence bigint not null,
    left_child_node_id varchar(64),
    right_child_node_id varchar(64),
    summary_document jsonb not null,
    estimated_token_count integer not null,
    summary_schema_version integer not null,
    summary_prompt_version integer not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_summary_node_identity unique (
        tenant_id,
        conversation_session_id,
        level,
        start_conversation_message_sequence,
        end_conversation_message_sequence,
        summary_schema_version,
        summary_prompt_version,
        model_provider,
        model_name),
    constraint ck_hierarchical_summary_node_non_negative check (
        level >= 0
        and covered_completed_turn_count > 0
        and start_conversation_message_sequence >= 0
        and end_conversation_message_sequence >= 0
        and estimated_token_count >= 0),
    constraint ck_hierarchical_summary_node_range check (
        start_conversation_message_sequence <= end_conversation_message_sequence),
    constraint ck_hierarchical_summary_node_shape check (
        (level = 0
            and covered_completed_turn_count = 1
            and left_child_node_id is null
            and right_child_node_id is null)
        or
        (level > 0
            and covered_completed_turn_count = power(2::numeric, level)
            and left_child_node_id is not null
            and right_child_node_id is not null)),
    constraint ck_hierarchical_summary_node_versions check (
        summary_schema_version > 0 and summary_prompt_version > 0),
    constraint fk_hierarchical_summary_node_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

create table conversation_context_hierarchical_compaction_job (
    id bigint generated always as identity primary key,
    hierarchical_compaction_job_id varchar(64) not null unique,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    execution_attempt bigint not null,
    owner_instance_id varchar(128) not null,
    input_up_to_message_sequence bigint not null,
    base_checkpoint_version bigint not null,
    base_strategy_generation bigint not null,
    plan_document jsonb not null,
    status varchar(32) not null,
    failure_code varchar(64),
    completed_at timestamp,
    created_at timestamp not null,
    created_by varchar(64) not null,
    updated_at timestamp not null,
    updated_by varchar(64) not null,
    deleted boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    constraint uk_hierarchical_compaction_job_boundary unique (
        tenant_id, execution_id, execution_attempt, input_up_to_message_sequence),
    constraint ck_hierarchical_compaction_job_boundaries check (
        execution_attempt > 0
        and input_up_to_message_sequence >= 0
        and base_checkpoint_version >= 0
        and base_strategy_generation >= 0),
    constraint ck_hierarchical_compaction_job_status check (
        status in (
            'PLANNED',
            'GENERATING',
            'READY_TO_PUBLISH',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED')),
    constraint fk_hierarchical_compaction_job_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id)
);

comment on table conversation_context_hierarchical_execution_policy is
    'execution 首次使用时冻结的 hierarchical 上下文策略。';
comment on table conversation_context_hierarchical_checkpoint is
    '会话当前正式发布的 hierarchical Cold 摘要和 Warm frontier。';
comment on table conversation_context_hierarchical_summary_node is
    'hierarchical Warm 层的不可变二进制摘要节点。';
comment on table conversation_context_hierarchical_compaction_job is
    '带 execution fencing 的可恢复 hierarchical 压缩作业。';

comment on column conversation_context_hierarchical_execution_policy.id is
    '数据库代理主键，仅用于持久化层内部定位策略记录。';
comment on column conversation_context_hierarchical_execution_policy.hierarchical_execution_policy_id is
    '冻结策略记录的业务唯一标识。';
comment on column conversation_context_hierarchical_execution_policy.tenant_id is
    '策略记录所属租户，用于租户隔离。';
comment on column conversation_context_hierarchical_execution_policy.conversation_session_id is
    '策略记录所属对话会话。';
comment on column conversation_context_hierarchical_execution_policy.execution_id is
    '首次进入 hierarchical 策略的 execution 标识。';
comment on column conversation_context_hierarchical_execution_policy.policy_document is
    '首次进入 execution 时冻结的不可变策略快照 JSON 文档。';
comment on column conversation_context_hierarchical_execution_policy.created_at is
    '策略记录首次冻结时间。';
comment on column conversation_context_hierarchical_execution_policy.created_by is
    '创建策略记录的插件操作者标识。';
comment on column conversation_context_hierarchical_execution_policy.updated_at is
    '策略记录最后更新时间。';
comment on column conversation_context_hierarchical_execution_policy.updated_by is
    '最后更新策略记录的插件操作者标识。';
comment on column conversation_context_hierarchical_execution_policy.deleted is
    '策略记录逻辑删除标记。';
comment on column conversation_context_hierarchical_execution_policy.version is
    '策略记录的非负乐观锁版本。';

comment on column conversation_context_hierarchical_checkpoint.id is
    '数据库代理主键，仅用于持久化层内部定位检查点。';
comment on column conversation_context_hierarchical_checkpoint.hierarchical_checkpoint_id is
    '检查点的业务唯一标识。';
comment on column conversation_context_hierarchical_checkpoint.tenant_id is
    '检查点所属租户，用于租户隔离。';
comment on column conversation_context_hierarchical_checkpoint.conversation_session_id is
    '检查点所属会话，每个租户会话只有一条有效记录。';
comment on column conversation_context_hierarchical_checkpoint.cold_summary_document is
    '当前正式发布的结构化 Cold 摘要 JSON 文档。';
comment on column conversation_context_hierarchical_checkpoint.summarized_up_to_conversation_message_sequence is
    'Cold 摘要已覆盖的最大非负会话消息序号，只能单调前进。';
comment on column conversation_context_hierarchical_checkpoint.summarized_completed_turn_count is
    'Cold 摘要已覆盖的非负完整问答数量。';
comment on column conversation_context_hierarchical_checkpoint.estimated_token_count is
    'Cold 摘要的非负保守 token 估算。';
comment on column conversation_context_hierarchical_checkpoint.summary_schema_version is
    'Cold 摘要文档使用的正 schema 版本。';
comment on column conversation_context_hierarchical_checkpoint.summary_prompt_version is
    '生成 Cold 摘要时使用的正提示词版本。';
comment on column conversation_context_hierarchical_checkpoint.model_provider is
    '生成 Cold 摘要的模型提供商。';
comment on column conversation_context_hierarchical_checkpoint.model_name is
    '生成 Cold 摘要的模型名称。';
comment on column conversation_context_hierarchical_checkpoint.active_frontier_document is
    '当前正式发布的无重叠 Warm 节点 frontier JSON 文档。';
comment on column conversation_context_hierarchical_checkpoint.strategy_generation is
    '每次成功发布单调递增的非负策略代次。';
comment on column conversation_context_hierarchical_checkpoint.created_at is
    '检查点记录创建时间。';
comment on column conversation_context_hierarchical_checkpoint.created_by is
    '创建检查点记录的插件操作者标识。';
comment on column conversation_context_hierarchical_checkpoint.updated_at is
    '检查点记录最后更新时间。';
comment on column conversation_context_hierarchical_checkpoint.updated_by is
    '最后更新检查点记录的插件操作者标识。';
comment on column conversation_context_hierarchical_checkpoint.deleted is
    '检查点记录逻辑删除标记。';
comment on column conversation_context_hierarchical_checkpoint.version is
    '检查点 CAS 使用的非负乐观锁版本。';

comment on column conversation_context_hierarchical_summary_node.id is
    '数据库代理主键，仅用于持久化层内部定位摘要节点。';
comment on column conversation_context_hierarchical_summary_node.hierarchical_summary_node_id is
    '不可变摘要节点的业务唯一标识。';
comment on column conversation_context_hierarchical_summary_node.tenant_id is
    '摘要节点所属租户，用于租户隔离。';
comment on column conversation_context_hierarchical_summary_node.conversation_session_id is
    '摘要节点所属对话会话。';
comment on column conversation_context_hierarchical_summary_node.level is
    '二进制摘要树中的非负层级。';
comment on column conversation_context_hierarchical_summary_node.covered_completed_turn_count is
    '节点覆盖的完整问答数量，必须等于 2 的 level 次方。';
comment on column conversation_context_hierarchical_summary_node.start_conversation_message_sequence is
    '节点覆盖的首个非负会话消息序号。';
comment on column conversation_context_hierarchical_summary_node.end_conversation_message_sequence is
    '节点覆盖的最后一个非负会话消息序号。';
comment on column conversation_context_hierarchical_summary_node.left_child_node_id is
    '非叶节点的左侧直接子节点业务标识。';
comment on column conversation_context_hierarchical_summary_node.right_child_node_id is
    '非叶节点的右侧直接子节点业务标识。';
comment on column conversation_context_hierarchical_summary_node.summary_document is
    '经过 schema 校验的结构化摘要 JSON 文档。';
comment on column conversation_context_hierarchical_summary_node.estimated_token_count is
    '摘要正文的非负保守 token 估算。';
comment on column conversation_context_hierarchical_summary_node.summary_schema_version is
    '摘要正文使用的正 schema 版本。';
comment on column conversation_context_hierarchical_summary_node.summary_prompt_version is
    '生成摘要时使用的正提示词版本。';
comment on column conversation_context_hierarchical_summary_node.model_provider is
    '生成摘要的模型提供商。';
comment on column conversation_context_hierarchical_summary_node.model_name is
    '生成摘要的模型名称。';
comment on column conversation_context_hierarchical_summary_node.created_at is
    '不可变摘要节点创建时间。';
comment on column conversation_context_hierarchical_summary_node.created_by is
    '创建摘要节点的插件操作者标识。';
comment on column conversation_context_hierarchical_summary_node.updated_at is
    '摘要节点持久化记录最后更新时间。';
comment on column conversation_context_hierarchical_summary_node.updated_by is
    '最后更新摘要节点记录的插件操作者标识。';
comment on column conversation_context_hierarchical_summary_node.deleted is
    '摘要节点逻辑删除标记；不可变节点正常情况下不删除。';
comment on column conversation_context_hierarchical_summary_node.version is
    '摘要节点持久化记录的非负乐观锁版本。';

comment on column conversation_context_hierarchical_compaction_job.id is
    '数据库代理主键，仅用于持久化层内部定位压缩作业。';
comment on column conversation_context_hierarchical_compaction_job.hierarchical_compaction_job_id is
    '压缩作业的业务唯一标识。';
comment on column conversation_context_hierarchical_compaction_job.tenant_id is
    '压缩作业所属租户，用于租户隔离。';
comment on column conversation_context_hierarchical_compaction_job.conversation_session_id is
    '压缩作业所属对话会话。';
comment on column conversation_context_hierarchical_compaction_job.execution_id is
    '创建或恢复压缩作业的 execution 标识。';
comment on column conversation_context_hierarchical_compaction_job.execution_attempt is
    '作业 fencing 使用的正 execution 尝试序号。';
comment on column conversation_context_hierarchical_compaction_job.owner_instance_id is
    '当前有权推动作业状态的 execution owner 实例标识。';
comment on column conversation_context_hierarchical_compaction_job.input_up_to_message_sequence is
    '规划时冻结的非负最高输入消息序号。';
comment on column conversation_context_hierarchical_compaction_job.base_checkpoint_version is
    '规划依据的非负 checkpoint 乐观锁版本。';
comment on column conversation_context_hierarchical_compaction_job.base_strategy_generation is
    '规划依据的非负已发布策略代次。';
comment on column conversation_context_hierarchical_compaction_job.plan_document is
    '不包含消息、思考、工具载荷或摘要正文的压缩计划 JSON 文档。';
comment on column conversation_context_hierarchical_compaction_job.status is
    '可恢复作业的受约束生命周期状态。';
comment on column conversation_context_hierarchical_compaction_job.failure_code is
    '失败时记录的安全稳定错误码。';
comment on column conversation_context_hierarchical_compaction_job.completed_at is
    '作业进入终态的时间。';
comment on column conversation_context_hierarchical_compaction_job.created_at is
    '压缩作业创建时间。';
comment on column conversation_context_hierarchical_compaction_job.created_by is
    '创建压缩作业记录的插件操作者标识。';
comment on column conversation_context_hierarchical_compaction_job.updated_at is
    '压缩作业记录最后更新时间。';
comment on column conversation_context_hierarchical_compaction_job.updated_by is
    '最后更新压缩作业记录的插件操作者标识。';
comment on column conversation_context_hierarchical_compaction_job.deleted is
    '压缩作业逻辑删除标记。';
comment on column conversation_context_hierarchical_compaction_job.version is
    '状态转换 CAS 使用的非负乐观锁版本。';
