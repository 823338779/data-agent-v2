-- 载荷随会话物理删除；逻辑可见性由同会话读取入口统一检查。
create table conversation_context_payload (
    reference varchar(64) primary key,
    tenant_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    execution_id varchar(64) not null,
    process_item_id varchar(64) not null,
    direction varchar(8) not null check (direction in ('INPUT', 'OUTPUT')),
    sha256 varchar(64) not null,
    total_bytes bigint not null check (total_bytes >= 0),
    content text not null,
    created_at timestamp not null default current_timestamp,
    unique (tenant_id, conversation_session_id, execution_id, process_item_id, direction, sha256),
    foreign key (tenant_id, conversation_session_id)
        references conversation_session (tenant_id, conversation_session_id) on delete cascade
);
comment on table conversation_context_payload is '工具输入和输出的安全完整原文，仅 Hierarchical V3 使用';
comment on column conversation_context_payload.reference is '服务端生成的不透明读取标识';
comment on column conversation_context_payload.tenant_id is '来源租户';
comment on column conversation_context_payload.conversation_session_id is '来源会话，读取与保留周期随会话';
comment on column conversation_context_payload.execution_id is '产生原始工具调用的执行';
comment on column conversation_context_payload.process_item_id is '真实 thinking process 节点标识';
comment on column conversation_context_payload.direction is 'INPUT 工具参数或 OUTPUT 工具结果';
comment on column conversation_context_payload.sha256 is '安全原文 SHA-256';
comment on column conversation_context_payload.total_bytes is '安全原文 UTF-8 字节数';
comment on column conversation_context_payload.content is '已经脱敏的完整安全正文';
comment on column conversation_context_payload.created_at is '首次归档时间';
