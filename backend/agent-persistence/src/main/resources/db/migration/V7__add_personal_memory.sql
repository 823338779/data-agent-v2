create table personal_memory (
    id bigint generated always as identity primary key,
    memory_id varchar(64) not null,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    kind varchar(32) not null,
    content text not null,
    normalized_hash varchar(64) not null,
    normalization_version integer not null,
    char_count integer not null,
    source varchar(32) not null,
    user_protected boolean not null,
    version bigint not null,
    last_confirmed_at timestamp,
    prompt_version varchar(64),
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint uq_personal_memory_id unique (tenant_id,user_id,memory_id),
    constraint fk_personal_memory_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_kind check (kind in ('person_profile','collaboration_preference')),
    constraint ck_personal_memory_source check (source in ('user_ui','user_direct','llm_active','background_review')),
    constraint ck_personal_memory_versions check (normalization_version>0 and char_count>0 and version>0)
);
create unique index uq_personal_memory_content on personal_memory(tenant_id,user_id,kind,normalized_hash);

create table personal_memory_state (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    revision bigint not null default 0,
    settings_version bigint not null default 0,
    read_enabled boolean not null default true,
    explicit_write_enabled boolean not null default true,
    auto_learning_enabled boolean not null default true,
    learning_epoch bigint not null default 1,
    learning_enabled_at timestamp,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint uq_personal_memory_state unique (tenant_id,user_id),
    constraint fk_personal_memory_state_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_state_versions check (revision>=0 and settings_version>=0 and learning_epoch>0)
);

create table personal_memory_operation (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    request_id varchar(256) not null,
    payload_hash varchar(64) not null,
    outcome_status varchar(32) not null,
    memory_id varchar(64),
    result_version bigint,
    result_revision bigint not null,
    created boolean not null,
    target_exists boolean not null,
    person_profile_used integer,
    person_profile_limit integer,
    collaboration_preference_used integer,
    collaboration_preference_limit integer,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint fk_personal_memory_operation_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_operation_revision check (result_revision>=0 and (result_version is null or result_version>0))
);
create unique index uq_personal_memory_operation on personal_memory_operation(tenant_id,user_id,request_id);

create table personal_memory_evidence (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    memory_id varchar(64) not null,
    evidence_id varchar(128) not null,
    kind varchar(32) not null,
    conversation_id varchar(64),
    execution_id varchar(64),
    sequence bigint not null,
    author_user_id varchar(64),
    message_version bigint not null,
    evidence_created_at timestamp not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint uq_personal_memory_evidence unique (tenant_id,user_id,memory_id,kind,evidence_id),
    constraint fk_personal_memory_evidence_memory foreign key (tenant_id,user_id,memory_id)
        references personal_memory(tenant_id,user_id,memory_id) on delete cascade,
    constraint ck_personal_memory_evidence_kind check (kind in ('user_message','ui_operation','assistant_context')),
    constraint ck_personal_memory_evidence_versions check (sequence>=0 and message_version>=0)
);

create table personal_memory_suppression (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    normalization_version integer not null,
    normalized_hash varchar(64) not null,
    deleted_revision bigint not null,
    deleted_at timestamp not null,
    delete_operation_id varchar(256) not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint fk_personal_memory_suppression_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_suppression_versions check (normalization_version>0 and deleted_revision>0)
);
create unique index uq_personal_memory_suppression on personal_memory_suppression(tenant_id,user_id,normalization_version,normalized_hash);

create table personal_memory_review_job (
    id bigint generated always as identity primary key,
    job_id varchar(64) not null,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    conversation_id varchar(64) not null,
    learning_epoch bigint not null,
    reviewed_through_sequence bigint not null default 0,
    pending_through_sequence bigint not null,
    claimed_through_sequence bigint not null default 0,
    dirty_generation bigint not null,
    claimed_generation bigint not null default 0,
    status varchar(16) not null,
    available_at timestamp not null,
    failure_count integer not null default 0,
    failure_reason varchar(64),
    owner varchar(128),
    fencing_token bigint not null default 0,
    lease_until timestamptz,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint uq_personal_memory_review_job_id unique (tenant_id,user_id,job_id),
    constraint uq_personal_memory_review_job_conversation unique (tenant_id,user_id,conversation_id),
    constraint fk_personal_memory_review_job_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_review_job_status check (status in ('PENDING','RUNNING','COMPLETED','FAILED','BLOCKED','CANCELLED')),
    constraint ck_personal_memory_review_job_bounds check (learning_epoch>0 and reviewed_through_sequence>=0 and pending_through_sequence>=reviewed_through_sequence and claimed_through_sequence>=reviewed_through_sequence and dirty_generation>0 and claimed_generation>=0 and failure_count>=0 and fencing_token>=0)
);

create table personal_memory_review_lease (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    fencing_token bigint not null default 0,
    owner varchar(128),
    lease_until timestamptz,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    constraint uq_personal_memory_review_lease unique (tenant_id,user_id),
    constraint fk_personal_memory_review_lease_user foreign key (tenant_id,user_id) references user_account(tenant_id,user_id),
    constraint ck_personal_memory_review_lease_token check (fencing_token>=0)
);

comment on table personal_memory is '固定身份跨会话共享的活动个人记忆正文；删除采用物理删除。';
comment on table personal_memory_state is '固定身份的记忆版本、设置版本与能力开关，也是统一写入锁行。';
comment on table personal_memory_operation is '稳定请求的幂等结果，不复制个人记忆正文。';
comment on table personal_memory_evidence is '活动记忆与证据元数据的关联，不复制聊天正文。';
comment on table personal_memory_suppression is '物理删除后保留的精确规范化指纹，跨分类阻止旧证据恢复。';
comment on table personal_memory_review_job is '按会话推进连续游标并支持租约接管的后台审查任务。';
comment on table personal_memory_review_lease is '固定用户的后台审查租约与单调 fencing token。';

comment on column personal_memory.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory.memory_id is '个人记忆业务唯一标识。';
comment on column personal_memory.tenant_id is '记忆所属固定租户。';
comment on column personal_memory.user_id is '记忆所属固定用户。';
comment on column personal_memory.kind is '分类：person_profile 或 collaboration_preference。';
comment on column personal_memory.content is '用户可见原文；删除时必须物理移除。';
comment on column personal_memory.normalized_hash is '规范化正文的 SHA-256 指纹。';
comment on column personal_memory.normalization_version is '规范化算法版本。';
comment on column personal_memory.char_count is 'Java UTF-16 code unit 计数，不能用 SQL 字符长度代替。';
comment on column personal_memory.source is '最初写入来源，后续保护升级不覆盖。';
comment on column personal_memory.user_protected is '人工创建、编辑或确认后为真，自动路径不得覆盖。';
comment on column personal_memory.version is '条目正文、保护或确认变化时递增的 CAS 版本。';
comment on column personal_memory.last_confirmed_at is '最近一次明确确认时间；从未确认时为空。';
comment on column personal_memory.prompt_version is '产生或确认该条目的提示词版本；人工路径可为空。';
comment on column personal_memory.created_at is '记录创建时的数据库本地时间。';
comment on column personal_memory.updated_at is '记录最后更新时的数据库本地时间。';

comment on column personal_memory_state.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_state.tenant_id is '状态所属固定租户。';
comment on column personal_memory_state.user_id is '状态所属固定用户。';
comment on column personal_memory_state.revision is '正文、保护、确认或设置变化时递增的整体版本。';
comment on column personal_memory_state.settings_version is '仅能力设置变化时递增的 CAS 版本。';
comment on column personal_memory_state.read_enabled is '是否允许向模型上下文注入记忆。';
comment on column personal_memory_state.explicit_write_enabled is '是否允许人工管理与用户明确写入。';
comment on column personal_memory_state.auto_learning_enabled is '是否允许主 Agent 推断与后台学习写入。';
comment on column personal_memory_state.learning_epoch is '学习开关切换时递增，使旧后台任务失效。';
comment on column personal_memory_state.learning_enabled_at is '当前学习代次启用时间；关闭时可为空。';
comment on column personal_memory_state.created_at is '记录创建时的数据库本地时间。';
comment on column personal_memory_state.updated_at is '记录最后更新时的数据库本地时间。';

comment on column personal_memory_operation.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_operation.tenant_id is '操作所属固定租户。';
comment on column personal_memory_operation.user_id is '操作所属固定用户。';
comment on column personal_memory_operation.request_id is '调用方稳定幂等请求标识。';
comment on column personal_memory_operation.payload_hash is '不含正文副本的规范化请求摘要。';
comment on column personal_memory_operation.outcome_status is '结构化业务结果状态。';
comment on column personal_memory_operation.memory_id is '结果涉及的记忆标识；无目标时为空。';
comment on column personal_memory_operation.result_version is '提交后的条目版本；无条目版本时为空。';
comment on column personal_memory_operation.result_revision is '提交结果对应的整体 revision。';
comment on column personal_memory_operation.created is '该操作是否新建了条目。';
comment on column personal_memory_operation.target_exists is '结果重放时目标在提交当时是否存在。';
comment on column personal_memory_operation.person_profile_used is '结果中的个人画像 UTF-16 已用量；未返回配额时为空。';
comment on column personal_memory_operation.person_profile_limit is '结果中的个人画像 UTF-16 上限；未返回配额时为空。';
comment on column personal_memory_operation.collaboration_preference_used is '结果中的协作偏好 UTF-16 已用量；未返回配额时为空。';
comment on column personal_memory_operation.collaboration_preference_limit is '结果中的协作偏好 UTF-16 上限；未返回配额时为空。';
comment on column personal_memory_operation.created_at is '操作首次提交时的数据库本地时间。';
comment on column personal_memory_operation.updated_at is '记录最后更新时的数据库本地时间。';

comment on column personal_memory_evidence.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_evidence.tenant_id is '证据关联所属固定租户。';
comment on column personal_memory_evidence.user_id is '证据关联所属固定用户。';
comment on column personal_memory_evidence.memory_id is '活动记忆业务标识；记忆物理删除时级联删除。';
comment on column personal_memory_evidence.evidence_id is '稳定证据业务标识。';
comment on column personal_memory_evidence.kind is '证据类型：用户消息、界面操作或助手语境。';
comment on column personal_memory_evidence.conversation_id is '证据所属会话；界面操作可为空。';
comment on column personal_memory_evidence.execution_id is '证据所属执行；界面操作可为空。';
comment on column personal_memory_evidence.sequence is '证据在会话内的持久化序号。';
comment on column personal_memory_evidence.author_user_id is '证据作者；非用户证据可为空。';
comment on column personal_memory_evidence.message_version is '来源消息或操作的版本。';
comment on column personal_memory_evidence.evidence_created_at is '来源证据原始创建时间。';
comment on column personal_memory_evidence.created_at is '关联记录创建时的数据库本地时间。';
comment on column personal_memory_evidence.updated_at is '关联记录最后更新时的数据库本地时间。';

comment on column personal_memory_suppression.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_suppression.tenant_id is '抑制记录所属固定租户。';
comment on column personal_memory_suppression.user_id is '抑制记录所属固定用户。';
comment on column personal_memory_suppression.normalization_version is '生成删除指纹时的规范化算法版本。';
comment on column personal_memory_suppression.normalized_hash is '被删除正文的规范化指纹，不包含正文。';
comment on column personal_memory_suppression.deleted_revision is '物理删除提交后的整体 revision。';
comment on column personal_memory_suppression.deleted_at is '物理删除发生时间。';
comment on column personal_memory_suppression.delete_operation_id is '产生抑制记录的稳定操作标识。';
comment on column personal_memory_suppression.created_at is '抑制记录创建时的数据库本地时间。';
comment on column personal_memory_suppression.updated_at is '抑制记录最后更新时的数据库本地时间。';

comment on column personal_memory_review_job.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_review_job.job_id is '审查任务业务唯一标识。';
comment on column personal_memory_review_job.tenant_id is '任务所属固定租户。';
comment on column personal_memory_review_job.user_id is '任务所属固定用户。';
comment on column personal_memory_review_job.conversation_id is '任务所属会话业务标识。';
comment on column personal_memory_review_job.learning_epoch is '任务所属学习代次，切换开关后旧代次失效。';
comment on column personal_memory_review_job.reviewed_through_sequence is '已连续处理到的包含上界，也是下一批排除下界。';
comment on column personal_memory_review_job.pending_through_sequence is '已提交事件要求处理到的包含上界。';
comment on column personal_memory_review_job.claimed_through_sequence is '本次领取冻结的包含上界。';
comment on column personal_memory_review_job.dirty_generation is '新完成事件递增的任务代数。';
comment on column personal_memory_review_job.claimed_generation is '当前领取冻结的任务代数。';
comment on column personal_memory_review_job.status is '任务状态：待处理、运行、完成、失败、阻塞或取消。';
comment on column personal_memory_review_job.available_at is '任务允许下次领取的数据库本地时间。';
comment on column personal_memory_review_job.failure_count is '当前批次累计失败次数，包含首次。';
comment on column personal_memory_review_job.failure_reason is '稳定失败或阻塞原因码，不含敏感正文。';
comment on column personal_memory_review_job.owner is '当前 worker 的稳定所有者标识；未领取时为空。';
comment on column personal_memory_review_job.fencing_token is '任务级单调 fencing token，阻止旧领取提交。';
comment on column personal_memory_review_job.lease_until is '任务租约数据库绝对到期时间。';
comment on column personal_memory_review_job.created_at is '任务创建时的数据库本地时间。';
comment on column personal_memory_review_job.updated_at is '任务最后更新时的数据库本地时间。';

comment on column personal_memory_review_lease.id is '数据库代理主键，不对外暴露。';
comment on column personal_memory_review_lease.tenant_id is '用户租约所属固定租户。';
comment on column personal_memory_review_lease.user_id is '用户租约所属固定用户，也是唯一租约范围。';
comment on column personal_memory_review_lease.fencing_token is '用户级单调 fencing token，释放后也不清零。';
comment on column personal_memory_review_lease.owner is '当前 worker 的稳定所有者标识；释放时为空。';
comment on column personal_memory_review_lease.lease_until is '用户租约数据库绝对到期时间；释放时为空。';
comment on column personal_memory_review_lease.created_at is '租约行创建时的数据库本地时间。';
comment on column personal_memory_review_lease.updated_at is '租约行最后更新时的数据库本地时间。';
