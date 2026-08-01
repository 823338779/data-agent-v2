-- 将含义隐晦的 Agent/Run 表名改成明确的会话、执行和检查点名称。
alter table agent_session rename to conversation_session;
alter table agent_message rename to conversation_message;
alter table agent_run rename to agent_execution;
alter table agent_run_attempt rename to agent_execution_attempt;
alter table agent_run_event rename to agent_execution_event;
alter table agent_run_stream rename to agent_execution_stream_checkpoint;
alter table agent_model_turn rename to model_invocation;
alter table agent_tool_execution rename to tool_execution;
alter table notification_outbox rename to notification_outbox_message;

-- 将各实体的业务标识列改成实体专属名称，避免 id 的归属依赖调用上下文猜测。
alter table tenant_membership rename column membership_id to tenant_membership_id;
alter table conversation_session rename column session_id to conversation_session_id;
alter table conversation_message rename column message_id to conversation_message_id;
alter table agent_execution rename column run_id to execution_id;
alter table agent_execution_attempt rename column attempt_id to execution_attempt_id;
alter table agent_execution_event rename column event_id to execution_event_id;
alter table agent_execution_stream_checkpoint
    rename column stream_id to execution_stream_checkpoint_id;
alter table model_invocation rename column model_turn_id to model_invocation_id;
alter table notification_outbox_message
    rename column notification_id to notification_message_id;

-- 同步跨实体引用列，使列名直接表达所引用的业务实体。
alter table conversation_session rename column created_by to created_by_user_id;
alter table conversation_session
    rename column message_sequence to conversation_message_sequence;
alter table conversation_session rename column event_sequence to execution_event_sequence;
alter table conversation_message rename column session_id to conversation_session_id;
alter table conversation_message rename column run_id to execution_id;
alter table conversation_message rename column sequence to conversation_message_sequence;
alter table agent_execution rename column session_id to conversation_session_id;
alter table agent_execution
    rename column triggering_message_id to triggering_conversation_message_id;
alter table agent_execution rename column retry_of_run_id to retry_of_execution_id;
alter table agent_execution_event rename column session_id to conversation_session_id;
alter table agent_execution_event rename column run_id to execution_id;
alter table agent_execution_event rename column sequence to execution_event_sequence;
alter table agent_execution_attempt rename column run_id to execution_id;
alter table agent_execution_stream_checkpoint rename column run_id to execution_id;
alter table model_invocation rename column run_id to execution_id;
alter table tool_execution rename column run_id to execution_id;
alter table tool_execution
    rename column assistant_message_id to assistant_conversation_message_id;
alter table tool_execution
    rename column observation_message_id to observation_conversation_message_id;

-- 序号列显式说明所属层级，区分执行接管、模型调用、消息、事件和输出增量。
alter table agent_execution rename column execution_attempt to execution_attempt_number;
alter table agent_execution_attempt
    rename column execution_attempt to execution_attempt_number;
alter table agent_execution_stream_checkpoint
    rename column execution_attempt to execution_attempt_number;
alter table agent_execution_stream_checkpoint
    rename column model_turn_sequence to model_invocation_sequence;
alter table agent_execution_stream_checkpoint
    rename column stream_sequence to output_delta_sequence;
alter table model_invocation rename column model_turn_sequence to model_invocation_sequence;
alter table model_invocation rename column execution_attempt to execution_attempt_number;
alter table model_invocation
    rename column input_up_to_message_sequence
    to input_up_to_conversation_message_sequence;
alter table tool_execution rename column model_turn_sequence to model_invocation_sequence;
alter table tool_execution rename column execution_attempt to execution_attempt_number;

-- 将已持久化的协议值迁移到 execution 词汇，并收紧新的幂等请求类型约束。
update agent_execution
   set request_type = 'execution_retry'
 where request_type = 'run_retry';
alter table agent_execution drop constraint ck_agent_run_request_idempotency;
alter table agent_execution
    add constraint ck_agent_execution_request_idempotency check (
        (request_type is null and request_idempotency_key is null and request_payload_hash is null)
        or (
            request_type in ('message_submission', 'execution_retry')
            and request_idempotency_key is not null
            and request_payload_hash ~ '^[0-9a-f]{64}$'
        )
    );
update agent_execution_event
   set type = case type
       when 'run_queued' then 'execution_queued'
       when 'run_started' then 'execution_started'
       when 'run_done' then 'execution_done'
       when 'run_completed' then 'execution_completed'
       when 'run_failed' then 'execution_failed'
       when 'run_cancelled' then 'execution_cancelled'
       else type
   end;
update notification_outbox_message
   set notification_type = 'execution_cancellation_requested'
 where notification_type = 'run_control_requested';
update notification_outbox_message
   set aggregate_type = 'agent_execution'
 where aggregate_type = 'agent_run';

-- 约束和索引名称也使用新词汇，避免 PostgreSQL 元数据继续暴露旧的 Run 命名。
do $$
declare
    item record;
    renamed text;
begin
    for item in
        select conrelid::regclass as relation_name, conname
          from pg_constraint
         where connamespace = current_schema()::regnamespace
    loop
        renamed := item.conname;
        renamed := replace(renamed, 'agent_run_stream', 'agent_execution_stream_checkpoint');
        renamed := replace(renamed, 'agent_run_attempt', 'agent_execution_attempt');
        renamed := replace(renamed, 'agent_run_event', 'agent_execution_event');
        renamed := replace(renamed, 'agent_run', 'agent_execution');
        renamed := replace(renamed, 'agent_model_turn', 'model_invocation');
        renamed := replace(renamed, 'agent_tool_execution', 'tool_execution');
        renamed := replace(renamed, 'agent_session', 'conversation_session');
        renamed := replace(renamed, 'agent_message', 'conversation_message');
        renamed := replace(renamed, 'notification_outbox', 'notification_outbox_message');
        renamed := replace(renamed, 'model_turn', 'model_invocation');
        renamed := replace(renamed, 'message_sequence', 'conversation_message_sequence');
        renamed := replace(renamed, 'event_sequence', 'execution_event_sequence');
        renamed := replace(renamed, 'run_id', 'execution_id');
        renamed := replace(renamed, 'session_id', 'conversation_session_id');
        renamed := replace(renamed, 'message_id', 'conversation_message_id');
        renamed := replace(renamed, 'event_id', 'execution_event_id');
        renamed := replace(renamed, 'attempt_id', 'execution_attempt_id');
        if renamed <> item.conname then
            execute format(
                'alter table %s rename constraint %I to %I',
                item.relation_name,
                item.conname,
                renamed);
        end if;
    end loop;

    for item in
        select schemaname, indexname
          from pg_indexes
         where schemaname = current_schema()
           and indexname not in (select conname from pg_constraint)
    loop
        renamed := item.indexname;
        renamed := replace(renamed, 'agent_run_stream', 'agent_execution_stream_checkpoint');
        renamed := replace(renamed, 'agent_run_attempt', 'agent_execution_attempt');
        renamed := replace(renamed, 'agent_run_event', 'agent_execution_event');
        renamed := replace(renamed, 'agent_run', 'agent_execution');
        renamed := replace(renamed, 'agent_model_turn', 'model_invocation');
        renamed := replace(renamed, 'agent_tool_execution', 'tool_execution');
        renamed := replace(renamed, 'agent_session', 'conversation_session');
        renamed := replace(renamed, 'agent_message', 'conversation_message');
        renamed := replace(renamed, 'notification_outbox', 'notification_outbox_message');
        renamed := replace(renamed, 'message_sequence', 'conversation_message_sequence');
        renamed := replace(renamed, 'event_sequence', 'execution_event_sequence');
        if renamed <> item.indexname then
            execute format('alter index %I.%I rename to %I', item.schemaname, item.indexname, renamed);
        end if;
    end loop;
end $$;
