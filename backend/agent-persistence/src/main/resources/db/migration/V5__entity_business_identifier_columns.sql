-- 将通用 uuid 列改为实体专属业务标识列，保持 Java 字段与数据库列语义一致。
alter table tenant rename column uuid to tenant_id;
alter table user_account rename column uuid to user_id;
alter table tenant_membership rename column uuid to membership_id;
alter table agent_session rename column uuid to session_id;
alter table agent_message rename column uuid to message_id;
alter table agent_run rename column uuid to run_id;
alter table agent_run_event rename column uuid to event_id;
alter table agent_run_stream rename column uuid to stream_id;
alter table agent_run_attempt rename column uuid to attempt_id;
alter table notification_outbox rename column uuid to notification_id;

-- 同步唯一约束名称，避免数据库元数据继续暴露已经移除的 uuid 概念。
alter table tenant rename constraint uq_tenant_uuid to uq_tenant_tenant_id;
alter table user_account rename constraint uq_user_account_uuid to uq_user_account_user_id;
alter table user_account
    rename constraint uq_user_account_tenant_id to uq_user_account_tenant_user_id;
alter table tenant_membership
    rename constraint uq_tenant_membership_uuid to uq_tenant_membership_membership_id;
alter table tenant_membership
    rename constraint uq_tenant_membership_tenant_id
    to uq_tenant_membership_tenant_membership_id;
alter table agent_session
    rename constraint uq_agent_session_uuid to uq_agent_session_session_id;
alter table agent_session
    rename constraint uq_agent_session_tenant_id to uq_agent_session_tenant_session_id;
alter table agent_message
    rename constraint uq_agent_message_uuid to uq_agent_message_message_id;
alter table agent_message
    rename constraint uq_agent_message_tenant_id to uq_agent_message_tenant_message_id;
alter table agent_run rename constraint uq_agent_run_uuid to uq_agent_run_run_id;
alter table agent_run
    rename constraint uq_agent_run_tenant_id to uq_agent_run_tenant_run_id;
alter table agent_run_event
    rename constraint uq_agent_run_event_uuid to uq_agent_run_event_event_id;
alter table agent_run_event
    rename constraint uq_agent_run_event_tenant_id to uq_agent_run_event_tenant_event_id;
alter table agent_run_stream
    rename constraint uq_agent_run_stream_uuid to uq_agent_run_stream_stream_id;
alter table agent_run_attempt
    rename constraint uq_agent_run_attempt_uuid to uq_agent_run_attempt_attempt_id;
alter table notification_outbox
    rename constraint uq_notification_outbox_uuid to uq_notification_outbox_notification_id;
alter table notification_outbox
    rename constraint uq_notification_outbox_tenant_id
    to uq_notification_outbox_tenant_notification_id;
