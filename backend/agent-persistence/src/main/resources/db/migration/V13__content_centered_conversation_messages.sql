-- 扩展真实消息中心结构；旧 internal 列暂时保留，由停止旧写入的下一次 contract 迁移清理。
alter table conversation_message
    add column thinking_process jsonb not null default '[]'::jsonb,
    add constraint ck_conversation_message_thinking_process_array
        check (jsonb_typeof(thinking_process) = 'array');

comment on column conversation_message.thinking_process is
    '与真实消息正文绑定的有序 ProcessItem JSONB 数组';

alter table tool_execution add column tool_process_item_id uuid;

comment on column tool_execution.tool_process_item_id is
    '对应 assistant 中心消息 thinking_process 数组内的工具节点 UUID';

-- 迁移期间递归清理历史工具 JSON 中的敏感字段；哈希和大小只能基于清理后的安全值。
create or replace function pg_temp.sanitize_process_json(source jsonb)
returns jsonb
language plpgsql
immutable
as $$
declare
    kind text;
    sanitized jsonb;
begin
    if source is null then
        return null;
    end if;
    kind := jsonb_typeof(source);
    if kind = 'object' then
        select coalesce(
                   jsonb_object_agg(
                       item.key,
                       case
                           when lower(regexp_replace(item.key, '[^a-zA-Z0-9]', '', 'g')) = any (
                               array[
                                   'password', 'passwd', 'secret', 'token', 'accesstoken',
                                   'refreshtoken', 'authorization', 'cookie', 'setcookie',
                                   'connectionstring', 'jdbcurl'
                               ])
                               then to_jsonb('[REDACTED]'::text)
                           else pg_temp.sanitize_process_json(item.value)
                       end),
                   '{}'::jsonb)
          into sanitized
          from jsonb_each(source) item;
        return sanitized;
    end if;
    if kind = 'array' then
        select coalesce(
                   jsonb_agg(pg_temp.sanitize_process_json(item.value) order by item.ordinality),
                   '[]'::jsonb)
          into sanitized
          from jsonb_array_elements(source) with ordinality item(value, ordinality);
        return sanitized;
    end if;
    return source;
end
$$;

-- 每个 execution 先选择既有公开回答；没有公开回答时复用最早的 internal assistant。
create temporary table migrated_message_center (
    tenant_id varchar(64) not null,
    execution_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    conversation_message_id varchar(64) not null,
    primary key (tenant_id, execution_id)
) on commit drop;

insert into migrated_message_center(
    tenant_id, execution_id, conversation_session_id, conversation_message_id)
select distinct on (message.tenant_id, message.execution_id)
       message.tenant_id,
       message.execution_id,
       message.conversation_session_id,
       message.conversation_message_id
  from conversation_message message
 where message.execution_id is not null
   and message.role = 'assistant'
   and message.visibility = 'user'
   and message.is_deleted = false
 order by message.tenant_id, message.execution_id,
          message.conversation_message_sequence desc, message.id desc;

insert into migrated_message_center(
    tenant_id, execution_id, conversation_session_id, conversation_message_id)
select distinct on (message.tenant_id, message.execution_id)
       message.tenant_id,
       message.execution_id,
       message.conversation_session_id,
       message.conversation_message_id
  from conversation_message message
 where message.execution_id is not null
   and message.role = 'assistant'
   and message.visibility = 'internal'
   and message.is_deleted = false
   and not exists (
       select 1
         from migrated_message_center center
        where center.tenant_id = message.tenant_id
          and center.execution_id = message.execution_id)
 order by message.tenant_id, message.execution_id,
          message.conversation_message_sequence, message.id;

-- 完全没有 assistant 的活动执行获得唯一占位消息，并按会话当前序号继续分配。
create temporary table missing_message_center on commit drop as
select execution.tenant_id,
       execution.execution_id,
       execution.conversation_session_id,
       concat('conversation_message_', replace(gen_random_uuid()::text, '-', ''))
           as conversation_message_id,
       session.conversation_message_sequence
           + row_number() over (
               partition by execution.tenant_id, execution.conversation_session_id
               order by execution.id) as conversation_message_sequence
  from agent_execution execution
  join conversation_session session
    on session.tenant_id = execution.tenant_id
   and session.conversation_session_id = execution.conversation_session_id
 where execution.is_deleted = false
   and not exists (
       select 1
         from migrated_message_center center
        where center.tenant_id = execution.tenant_id
          and center.execution_id = execution.execution_id);

insert into conversation_message(
    conversation_message_id,
    tenant_id,
    conversation_session_id,
    execution_id,
    role,
    content,
    conversation_message_sequence,
    visibility,
    thinking_process)
select conversation_message_id,
       tenant_id,
       conversation_session_id,
       execution_id,
       'assistant',
       '',
       conversation_message_sequence,
       'user',
       '[]'::jsonb
  from missing_message_center;

insert into migrated_message_center(
    tenant_id, execution_id, conversation_session_id, conversation_message_id)
select tenant_id, execution_id, conversation_session_id, conversation_message_id
  from missing_message_center;

update conversation_session session
   set conversation_message_sequence = allocated.maximum_sequence,
       updated_at = localtimestamp,
       version = version + 1
  from (
      select tenant_id,
             conversation_session_id,
             max(conversation_message_sequence) as maximum_sequence
        from missing_message_center
       group by tenant_id, conversation_session_id) allocated
 where session.tenant_id = allocated.tenant_id
   and session.conversation_session_id = allocated.conversation_session_id;

-- 把历史工具调用和 observation 合并成一个强类型 TOOL ProcessItem。
create temporary table migrated_tool_process_item on commit drop as
with source as (
    select tool.tenant_id,
           tool.tool_execution_id,
           tool.execution_id,
           center.conversation_message_id as center_message_id,
           gen_random_uuid() as process_item_id,
           row_number() over (
               partition by tool.tenant_id, center.conversation_message_id
               order by tool.model_invocation_sequence,
                        assistant.conversation_message_sequence,
                        coalesce(call_entry.ordinality, 0),
                        tool.id) as process_sequence,
           tool.model_invocation_sequence,
           tool.tool_call_id,
           tool.tool_name,
           tool.status,
           tool.failure_code,
           tool.started_at,
           tool.completed_at,
           tool.created_at at time zone 'Asia/Shanghai' as created_at,
           pg_temp.sanitize_process_json(
               coalesce(call_entry.value -> 'arguments', '{}'::jsonb)) as safe_input,
           pg_temp.sanitize_process_json(observation.content::jsonb) as safe_output
      from tool_execution tool
      join migrated_message_center center
        on center.tenant_id = tool.tenant_id
       and center.execution_id = tool.execution_id
      join conversation_message assistant
        on assistant.tenant_id = tool.tenant_id
       and assistant.conversation_message_id = tool.assistant_conversation_message_id
      left join lateral jsonb_array_elements(assistant.tool_calls -> 'calls')
          with ordinality call_entry(value, ordinality)
        on call_entry.value ->> 'id' = tool.tool_call_id
      left join conversation_message observation
        on observation.tenant_id = tool.tenant_id
       and observation.conversation_message_id = tool.observation_conversation_message_id
     where tool.is_deleted = false
), documented as (
    select source.*,
           jsonb_build_object(
               'value', safe_input,
               'preview', null,
               'truncated', false,
               'originalSizeBytes', octet_length(convert_to(safe_input::text, 'UTF8')),
               'sha256', encode(sha256(convert_to(safe_input::text, 'UTF8')), 'hex'),
               'contentType', 'application/json') as input_document,
           case
               when safe_output is null then null
               else jsonb_build_object(
                   'value', safe_output,
                   'preview', null,
                   'truncated', false,
                   'originalSizeBytes', octet_length(convert_to(safe_output::text, 'UTF8')),
                   'sha256', encode(sha256(convert_to(safe_output::text, 'UTF8')), 'hex'),
                   'contentType', 'application/json')
           end as output_document
      from source
)
select tenant_id,
       tool_execution_id,
       center_message_id,
       process_item_id,
       process_sequence,
       jsonb_build_object(
           'processItemId', process_item_id,
           'sequence', process_sequence,
           'type', 'TOOL',
           'status', case status
               when 'succeeded' then 'SUCCEEDED'
               when 'failed' then 'FAILED'
               when 'executing' then 'RUNNING'
               else 'PREPARED'
           end,
           'createdAt', created_at,
           'startedAt', started_at,
           'completedAt', completed_at,
           'durationMillis', case
               when started_at is not null and completed_at is not null
                   then greatest(
                       0,
                       (extract(epoch from (completed_at - started_at)) * 1000)::bigint)
               else null
           end,
           'schemaVersion', 1,
           'toolId', tool_name,
           'toolName', tool_name,
           'toolExecutionId', tool_execution_id,
           'toolCallId', tool_call_id,
           'modelInvocationSequence', model_invocation_sequence,
           'input', input_document,
           'output', output_document,
           'success', case
               when status = 'succeeded' then true
               when status = 'failed' then false
               else null
           end,
           'errorCode', failure_code,
           'errorMessage', null) as process_document
  from documented;

update conversation_message message
   set thinking_process = message.thinking_process || aggregated.process_items,
       updated_at = localtimestamp,
       version = version + 1
  from (
      select tenant_id,
             center_message_id,
             jsonb_agg(process_document order by process_sequence) as process_items
        from migrated_tool_process_item
       group by tenant_id, center_message_id) aggregated
 where message.tenant_id = aggregated.tenant_id
   and message.conversation_message_id = aggregated.center_message_id;

update tool_execution tool
   set assistant_conversation_message_id = migrated.center_message_id,
       tool_process_item_id = migrated.process_item_id,
       updated_at = localtimestamp,
       version = version + 1
  from migrated_tool_process_item migrated
 where tool.tenant_id = migrated.tenant_id
   and tool.tool_execution_id = migrated.tool_execution_id;

-- 工具参数完成回填后再公开复用的 internal assistant，并清空与 user 可见形状冲突的旧载荷。
update conversation_message message
   set visibility = 'user',
       content = '',
       tool_calls = null,
       updated_at = localtimestamp,
       version = version + 1
  from migrated_message_center center
 where message.tenant_id = center.tenant_id
   and message.conversation_message_id = center.conversation_message_id
   and message.visibility = 'internal';
