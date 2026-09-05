create table artifact_build_job (
    id bigint generated always as identity primary key,
    artifact_build_job_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    conversation_message_id varchar(64) not null,
    artifact_type varchar(16) not null,
    operation varchar(16) not null,
    status varchar(32) not null,
    specification jsonb not null,
    template_id varchar(128) not null,
    template_version varchar(64) not null,
    skill_digest varchar(64) not null,
    script_digest varchar(64) not null,
    runtime_version varchar(128) not null,
    platform varchar(32) not null,
    failure_code varchar(64),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_artifact_build_job_id unique (artifact_build_job_id),
    constraint uq_artifact_build_job_tenant_id unique (tenant_id, artifact_build_job_id),
    constraint fk_artifact_build_job_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_artifact_build_job_session foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id),
    constraint fk_artifact_build_job_execution foreign key (tenant_id, execution_id)
        references agent_execution(tenant_id, execution_id),
    constraint fk_artifact_build_job_message foreign key (tenant_id, conversation_message_id)
        references conversation_message(tenant_id, conversation_message_id),
    constraint ck_artifact_build_job_type check (artifact_type in ('DOCX', 'PPTX', 'XLSX')),
    constraint ck_artifact_build_job_operation check (operation in ('CREATE', 'REVISE')),
    constraint ck_artifact_build_job_status check (status in (
        'PREPARED', 'RUNNING', 'RENDERING', 'VERIFYING',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    constraint ck_artifact_build_job_specification_object
        check (jsonb_typeof(specification) = 'object'),
    constraint ck_artifact_build_job_skill_digest
        check (skill_digest ~ '^[0-9a-f]{64}$'),
    constraint ck_artifact_build_job_script_digest
        check (script_digest ~ '^[0-9a-f]{64}$'),
    constraint ck_artifact_build_job_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table artifact_object (
    id bigint generated always as identity primary key,
    artifact_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    artifact_build_job_id varchar(64) not null,
    source_artifact_id varchar(64),
    specification_version integer not null,
    artifact_type varchar(16) not null,
    status varchar(16) not null,
    display_name varchar(256) not null,
    media_type varchar(160) not null,
    storage_key varchar(512) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    page_or_sheet_count integer,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_artifact_object_id unique (artifact_id),
    constraint uq_artifact_object_tenant_id unique (tenant_id, artifact_id),
    constraint uq_artifact_object_storage_key unique (storage_key),
    constraint fk_artifact_object_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_artifact_object_session foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id),
    constraint fk_artifact_object_build_job foreign key (tenant_id, artifact_build_job_id)
        references artifact_build_job(tenant_id, artifact_build_job_id),
    constraint fk_artifact_object_source foreign key (tenant_id, source_artifact_id)
        references artifact_object(tenant_id, artifact_id),
    constraint ck_artifact_object_type check (artifact_type in ('DOCX', 'PPTX', 'XLSX')),
    constraint ck_artifact_object_ready check (status = 'READY'),
    constraint ck_artifact_object_version check (specification_version > 0),
    constraint ck_artifact_object_size check (size_bytes > 0),
    constraint ck_artifact_object_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_artifact_object_count check (
        page_or_sheet_count is null or page_or_sheet_count > 0),
    constraint ck_artifact_object_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table conversation_message_artifact (
    id bigint generated always as identity primary key,
    conversation_message_artifact_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_message_id varchar(64) not null,
    artifact_id varchar(64) not null,
    display_order integer not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_conversation_message_artifact_id
        unique (conversation_message_artifact_id),
    constraint uq_conversation_message_artifact_tenant_id
        unique (tenant_id, conversation_message_artifact_id),
    constraint uq_conversation_message_artifact_relation
        unique (tenant_id, conversation_message_id, artifact_id),
    constraint fk_conversation_message_artifact_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_conversation_message_artifact_message
        foreign key (tenant_id, conversation_message_id)
        references conversation_message(tenant_id, conversation_message_id),
    constraint fk_conversation_message_artifact_artifact foreign key (tenant_id, artifact_id)
        references artifact_object(tenant_id, artifact_id),
    constraint ck_conversation_message_artifact_order check (display_order >= 0),
    constraint ck_conversation_message_artifact_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create table message_ui_block (
    id bigint generated always as identity primary key,
    ui_block_id varchar(64) not null,
    tenant_id varchar(64) not null,
    conversation_message_id varchar(64) not null,
    type varchar(64) not null,
    schema_version integer not null,
    variant varchar(64) not null,
    payload jsonb not null,
    display_order integer not null,
    created_at timestamp not null default localtimestamp,
    updated_at timestamp not null default localtimestamp,
    deleted_at timestamp,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_message_ui_block_id unique (ui_block_id),
    constraint uq_message_ui_block_tenant_id unique (tenant_id, ui_block_id),
    constraint fk_message_ui_block_tenant foreign key (tenant_id)
        references tenant(tenant_id),
    constraint fk_message_ui_block_message foreign key (tenant_id, conversation_message_id)
        references conversation_message(tenant_id, conversation_message_id),
    constraint ck_message_ui_block_schema_version check (schema_version > 0),
    constraint ck_message_ui_block_payload_object check (jsonb_typeof(payload) = 'object'),
    constraint ck_message_ui_block_order check (display_order >= 0),
    constraint ck_message_ui_block_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null))
);

create index ix_artifact_build_job_execution
    on artifact_build_job (tenant_id, execution_id, created_at)
    where is_deleted = false;

create index ix_artifact_object_session
    on artifact_object (tenant_id, conversation_session_id, created_at)
    where is_deleted = false;

create index ix_conversation_message_artifact_message
    on conversation_message_artifact (tenant_id, conversation_message_id, display_order)
    where is_deleted = false;

create index ix_message_ui_block_message
    on message_ui_block (tenant_id, conversation_message_id, display_order)
    where is_deleted = false;

comment on table artifact_build_job is '受控 Office 文档构建任务及其可恢复生命周期。';
comment on column artifact_build_job.id is '数据库代理主键，不对外暴露。';
comment on column artifact_build_job.artifact_build_job_id is '文档构建任务业务唯一标识。';
comment on column artifact_build_job.tenant_id is '构建任务所属租户。';
comment on column artifact_build_job.conversation_session_id is '构建任务所属会话。';
comment on column artifact_build_job.execution_id is '触发构建任务的 Agent 执行标识。';
comment on column artifact_build_job.conversation_message_id is '接收正式产物的 assistant 消息标识。';
comment on column artifact_build_job.artifact_type is '产物类型：DOCX、PPTX 或 XLSX。';
comment on column artifact_build_job.operation is '规格操作：CREATE 新建或 REVISE 修订。';
comment on column artifact_build_job.status is '任务状态：准备、运行、渲染、验证或终态。';
comment on column artifact_build_job.specification is '构建权威输入 ArtifactSpec，必须是 JSON 对象。';
comment on column artifact_build_job.template_id is '服务端内置模板的稳定标识。';
comment on column artifact_build_job.template_version is '内置模板版本，用于复现历史产物。';
comment on column artifact_build_job.skill_digest is '本次构建所用 Skill 内容的 SHA-256。';
comment on column artifact_build_job.script_digest is '本次构建所用固定脚本的 SHA-256。';
comment on column artifact_build_job.runtime_version is '受控 Python 与文档依赖运行时版本。';
comment on column artifact_build_job.platform is '实际选择的 Linux 或 Windows 沙箱平台。';
comment on column artifact_build_job.failure_code is '终态失败分类码；成功或未失败时为空。';
comment on column artifact_build_job.started_at is '沙箱实际开始执行的带时区时间。';
comment on column artifact_build_job.completed_at is '任务进入终态的带时区时间。';
comment on column artifact_build_job.created_at is '记录创建时的数据库本地时间。';
comment on column artifact_build_job.updated_at is '记录最后更新时的数据库本地时间。';
comment on column artifact_build_job.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column artifact_build_job.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column artifact_build_job.version is '任务状态迁移使用的乐观锁版本。';

comment on table artifact_object is '通过全部 QA 后发布的不可变 Office 文件元数据。';
comment on column artifact_object.id is '数据库代理主键，不对外暴露。';
comment on column artifact_object.artifact_id is '正式产物业务唯一标识。';
comment on column artifact_object.tenant_id is '正式产物所属租户。';
comment on column artifact_object.conversation_session_id is '正式产物所属会话。';
comment on column artifact_object.artifact_build_job_id is '生成该产物的构建任务标识。';
comment on column artifact_object.source_artifact_id is '修订来源产物标识；新建产物时为空。';
comment on column artifact_object.specification_version is 'ArtifactSpec 版本，从 1 开始递增。';
comment on column artifact_object.artifact_type is '产物类型：DOCX、PPTX 或 XLSX。';
comment on column artifact_object.status is '正式产物状态；首版固定为 READY。';
comment on column artifact_object.display_name is '面向用户显示和下载的文件名，不参与路径解析。';
comment on column artifact_object.media_type is '下载响应使用的固定 Office MIME 类型。';
comment on column artifact_object.storage_key is '受控根目录内由服务端生成的相对存储键。';
comment on column artifact_object.size_bytes is '正式文件字节数，必须为正数。';
comment on column artifact_object.sha256 is '正式文件小写十六进制 SHA-256。';
comment on column artifact_object.page_or_sheet_count is '页、幻灯片或工作表数量；不可用时为空。';
comment on column artifact_object.created_at is '记录创建时的数据库本地时间。';
comment on column artifact_object.updated_at is '不可变元数据的审计更新时间，通常等于创建时间。';
comment on column artifact_object.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column artifact_object.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column artifact_object.version is '元数据乐观锁版本。';

comment on table conversation_message_artifact is 'assistant 消息与正式产物之间的有序展示关系。';
comment on column conversation_message_artifact.id is '数据库代理主键，不对外暴露。';
comment on column conversation_message_artifact.conversation_message_artifact_id is '消息产物关系业务唯一标识。';
comment on column conversation_message_artifact.tenant_id is '消息产物关系所属租户。';
comment on column conversation_message_artifact.conversation_message_id is '承载下载卡片的消息标识。';
comment on column conversation_message_artifact.artifact_id is '消息关联的正式产物标识。';
comment on column conversation_message_artifact.display_order is '产物在消息中的展示顺序，从 0 开始。';
comment on column conversation_message_artifact.created_at is '记录创建时的数据库本地时间。';
comment on column conversation_message_artifact.updated_at is '记录最后更新时的数据库本地时间。';
comment on column conversation_message_artifact.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column conversation_message_artifact.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column conversation_message_artifact.version is '关系记录乐观锁版本。';

comment on table message_ui_block is '消息 Markdown 占位符对应的受控版本化 UI 描述。';
comment on column message_ui_block.id is '数据库代理主键，不对外暴露。';
comment on column message_ui_block.ui_block_id is 'UI Block 业务唯一标识，同时用于 Markdown 占位。';
comment on column message_ui_block.tenant_id is 'UI Block 所属租户。';
comment on column message_ui_block.conversation_message_id is 'UI Block 所属消息标识。';
comment on column message_ui_block.type is '前端白名单注册表中的组件类型。';
comment on column message_ui_block.schema_version is '组件载荷协议版本，从 1 开始。';
comment on column message_ui_block.variant is '服务端允许的组件外观变体。';
comment on column message_ui_block.payload is '服务端生成的受控组件引用载荷，必须是 JSON 对象。';
comment on column message_ui_block.display_order is '同一消息内 UI Block 的展示顺序，从 0 开始。';
comment on column message_ui_block.created_at is '记录创建时的数据库本地时间。';
comment on column message_ui_block.updated_at is '记录最后更新时的数据库本地时间。';
comment on column message_ui_block.deleted_at is '逻辑删除时间；未删除时为空。';
comment on column message_ui_block.is_deleted is '逻辑删除标记，必须与 deleted_at 同步。';
comment on column message_ui_block.version is 'UI Block 记录乐观锁版本。';
