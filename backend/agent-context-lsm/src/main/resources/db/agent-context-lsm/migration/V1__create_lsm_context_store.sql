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
    constraint ck_context_lsm_manifest_boundaries check (
        warm_cutoff_turn_sequence >= 0
        and hot_from_turn_sequence >= 0
        and evaluated_up_to_message_sequence >= 0),
    constraint ck_context_lsm_manifest_policy check (
        effective_policy_version > 0
        and effective_fanout between 3 and 10
        and effective_max_level between 1 and 8),
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

comment on column conversation_context_lsm_segment.id is '数据库代理主键，仅用于持久化层内部定位摘要段。';
comment on column conversation_context_lsm_segment.summary_segment_id is '不可变摘要段的业务唯一标识。';
comment on column conversation_context_lsm_segment.tenant_id is '摘要段所属租户，用于租户隔离。';
comment on column conversation_context_lsm_segment.conversation_session_id is '摘要段所属对话会话。';
comment on column conversation_context_lsm_segment.segment_key is '由摘要输入确定性计算的段幂等键。';
comment on column conversation_context_lsm_segment.kind is '段类型，STANDARD 表示普通层级段，ROOT 表示滚动根段。';
comment on column conversation_context_lsm_segment.level is '摘要段所在的非负 LSM 层级。';
comment on column conversation_context_lsm_segment.start_turn_sequence is '摘要段覆盖的首个轮次序号。';
comment on column conversation_context_lsm_segment.end_turn_sequence is '摘要段覆盖的最后轮次序号。';
comment on column conversation_context_lsm_segment.start_message_sequence is '摘要段覆盖的首个消息序号。';
comment on column conversation_context_lsm_segment.end_message_sequence is '摘要段覆盖的最后消息序号。';
comment on column conversation_context_lsm_segment.partial_segment is '摘要段是否包含尚未完整结束的轮次。';
comment on column conversation_context_lsm_segment.summary_document is '经过摘要 schema 校验后保存的摘要正文 JSONB。';
comment on column conversation_context_lsm_segment.estimated_token_count is '摘要正文的非负估算 token 数。';
comment on column conversation_context_lsm_segment.source_segment_ids is '生成该摘要段的来源摘要段业务标识 JSON 数组。';
comment on column conversation_context_lsm_segment.source_content_sha256 is '摘要来源正文的 SHA-256 内容指纹。';
comment on column conversation_context_lsm_segment.summary_schema_version is '摘要正文适用的 schema 版本。';
comment on column conversation_context_lsm_segment.prompt_version is '生成摘要时使用的提示词版本。';
comment on column conversation_context_lsm_segment.model_provider is '生成摘要的模型提供商。';
comment on column conversation_context_lsm_segment.model_name is '生成摘要的模型名称。';
comment on column conversation_context_lsm_segment.creation_policy is '创建摘要段时冻结的策略快照 JSONB。';
comment on column conversation_context_lsm_segment.source_segment_count is '构成该摘要段的非负来源段数量。';
comment on column conversation_context_lsm_segment.created_at is '摘要段记录创建时间。';
comment on column conversation_context_lsm_segment.created_by is '创建摘要段记录的操作者标识。';
comment on column conversation_context_lsm_segment.updated_at is '摘要段记录最后更新时间。';
comment on column conversation_context_lsm_segment.updated_by is '最后更新摘要段记录的操作者标识。';
comment on column conversation_context_lsm_segment.deleted is '逻辑删除标记；不可变摘要段通常不删除。';
comment on column conversation_context_lsm_segment.version is '摘要段持久化记录的乐观锁版本。';

comment on column conversation_context_lsm_manifest.id is '数据库代理主键，仅用于持久化层内部定位 manifest。';
comment on column conversation_context_lsm_manifest.context_manifest_id is '会话 LSM manifest 的业务唯一标识。';
comment on column conversation_context_lsm_manifest.tenant_id is 'manifest 所属租户，用于租户隔离。';
comment on column conversation_context_lsm_manifest.conversation_session_id is 'manifest 所属对话会话，每个租户会话唯一。';
comment on column conversation_context_lsm_manifest.active_root_segment_id is '当前发布 ROOT 摘要段的业务标识。';
comment on column conversation_context_lsm_manifest.active_segment_ids is '按覆盖区间排序的活动摘要段业务标识 JSON 数组。';
comment on column conversation_context_lsm_manifest.pending_raw_start_turn_sequence is '等待压缩的原始历史首轮序号。';
comment on column conversation_context_lsm_manifest.pending_raw_end_turn_sequence is '等待压缩的原始历史末轮序号。';
comment on column conversation_context_lsm_manifest.warm_cutoff_turn_sequence is 'Warm 上下文区间的非负截止轮次序号。';
comment on column conversation_context_lsm_manifest.hot_from_turn_sequence is 'Hot 上下文区间的非负起始轮次序号。';
comment on column conversation_context_lsm_manifest.evaluated_up_to_message_sequence is '已评估的非负最高消息序号。';
comment on column conversation_context_lsm_manifest.effective_policy_version is '当前 manifest 生效的正策略版本。';
comment on column conversation_context_lsm_manifest.effective_fanout is '当前 manifest 生效的策略合并扇出。';
comment on column conversation_context_lsm_manifest.effective_max_level is '当前 manifest 生效的策略最大 LSM 层级。';
comment on column conversation_context_lsm_manifest.generation is '每次发布单调递增的非负 manifest 代次。';
comment on column conversation_context_lsm_manifest.created_at is 'manifest 记录创建时间。';
comment on column conversation_context_lsm_manifest.created_by is '创建 manifest 记录的操作者标识。';
comment on column conversation_context_lsm_manifest.updated_at is 'manifest 记录最后更新时间。';
comment on column conversation_context_lsm_manifest.updated_by is '最后更新 manifest 记录的操作者标识。';
comment on column conversation_context_lsm_manifest.deleted is 'manifest 逻辑删除标记。';
comment on column conversation_context_lsm_manifest.version is 'manifest CAS 使用的乐观锁版本。';

comment on column conversation_context_lsm_job.id is '数据库代理主键，仅用于持久化层内部定位压缩作业。';
comment on column conversation_context_lsm_job.compaction_job_id is '可恢复压缩作业的业务唯一标识。';
comment on column conversation_context_lsm_job.tenant_id is '压缩作业所属租户，用于租户隔离。';
comment on column conversation_context_lsm_job.conversation_session_id is '压缩作业所属对话会话。';
comment on column conversation_context_lsm_job.execution_id is '创建或恢复压缩作业的 execution 标识。';
comment on column conversation_context_lsm_job.execution_attempt is '作业受 fencing 保护的 execution 尝试序号。';
comment on column conversation_context_lsm_job.input_up_to_message_sequence is '规划时冻结的非负最高输入消息序号。';
comment on column conversation_context_lsm_job.source_manifest_generation is '规划时读取到的非负 manifest generation。';
comment on column conversation_context_lsm_job.source_manifest_version is '规划时读取到的非负 manifest 乐观锁版本。';
comment on column conversation_context_lsm_job.policy_snapshot is '规划时冻结的策略快照 ContextPolicySnapshot JSONB。';
comment on column conversation_context_lsm_job.plan_document is '不保存正文的压缩计划 JSONB。';
comment on column conversation_context_lsm_job.plan_sha256 is '规范化压缩计划 JSON 的 SHA-256 指纹。';
comment on column conversation_context_lsm_job.status is '压缩作业生命周期状态。';
comment on column conversation_context_lsm_job.owner_instance_id is '当前拥有压缩作业的服务实例标识。';
comment on column conversation_context_lsm_job.failure_code is '压缩失败时记录的稳定失败代码。';
comment on column conversation_context_lsm_job.completed_at is '压缩作业进入终态的时间。';
comment on column conversation_context_lsm_job.created_at is '压缩作业记录创建时间。';
comment on column conversation_context_lsm_job.created_by is '创建压缩作业记录的操作者标识。';
comment on column conversation_context_lsm_job.updated_at is '压缩作业记录最后更新时间。';
comment on column conversation_context_lsm_job.updated_by is '最后更新压缩作业记录的操作者标识。';
comment on column conversation_context_lsm_job.deleted is '压缩作业逻辑删除标记。';
comment on column conversation_context_lsm_job.version is '压缩作业持久化记录的乐观锁版本。';

comment on column conversation_context_lsm_execution_policy.id is '数据库代理主键，仅用于持久化层内部定位 execution 策略。';
comment on column conversation_context_lsm_execution_policy.execution_policy_id is 'execution 策略快照的业务唯一标识。';
comment on column conversation_context_lsm_execution_policy.tenant_id is 'execution 策略所属租户，用于租户隔离。';
comment on column conversation_context_lsm_execution_policy.conversation_session_id is '首次使用策略的对话会话。';
comment on column conversation_context_lsm_execution_policy.execution_id is '首次使用并冻结策略的 execution 标识。';
comment on column conversation_context_lsm_execution_policy.policy_snapshot is 'execution 首次进入 LSM 时冻结的不可变策略快照 JSONB。';
comment on column conversation_context_lsm_execution_policy.created_at is 'execution 策略记录创建时间。';
comment on column conversation_context_lsm_execution_policy.created_by is '创建 execution 策略记录的操作者标识。';
comment on column conversation_context_lsm_execution_policy.updated_at is 'execution 策略记录最后更新时间。';
comment on column conversation_context_lsm_execution_policy.updated_by is '最后更新 execution 策略记录的操作者标识。';
comment on column conversation_context_lsm_execution_policy.deleted is 'execution 策略逻辑删除标记。';
comment on column conversation_context_lsm_execution_policy.version is 'execution 策略持久化记录的乐观锁版本。';
