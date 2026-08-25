-- request_type 已被 V2 收紧为唯一值 message_submission，无法再区分业务场景。
-- 幂等键的真实边界是租户 + 会话 + 调用方幂等键，因此删除该重复维度。
alter table agent_execution
    drop constraint uq_agent_execution_request_idempotency;

alter table agent_execution
    drop constraint ck_agent_execution_request_type;

alter table agent_execution
    drop column request_type;

alter table agent_execution
    add constraint uq_agent_execution_request_idempotency
    unique (tenant_id, conversation_session_id, request_idempotency_key);

comment on column agent_execution.request_idempotency_key is
    '调用方提供的幂等键，在同一租户和会话内唯一。';
