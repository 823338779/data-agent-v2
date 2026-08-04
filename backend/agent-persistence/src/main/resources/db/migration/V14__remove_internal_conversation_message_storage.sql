-- contract migration：工具过程已经进入 assistant 中心行，先移除旧 observation 双向外键和形状约束。
alter table tool_execution
    drop constraint fk_tool_execution_observation,
    drop constraint fk_tool_execution_assistant_message,
    drop constraint ck_tool_execution_terminal_observation;

alter table conversation_message
    drop constraint fk_conversation_message_tool_execution,
    drop constraint uq_conversation_message_tool_observation,
    drop constraint ck_conversation_message_tool_shape,
    drop constraint ck_conversation_message_tool_calls_shape,
    drop constraint ck_conversation_message_visibility,
    drop constraint ck_conversation_message_role;

-- 删除 V13 已合并进中心消息的 internal assistant/tool 行，真实正文行全部保留。
delete from conversation_message
 where visibility = 'internal'
    or role = 'tool';

-- V13 已把工具引用迁到正文 assistant；若历史执行曾产生多条公开 assistant，只保留最终正文所在行。
with ranked_assistant as (
    select tenant_id,
           conversation_message_id,
           row_number() over (
               partition by tenant_id, execution_id
               order by exists (
                            select 1
                              from tool_execution tool
                             where tool.tenant_id = conversation_message.tenant_id
                               and tool.assistant_conversation_message_id =
                                   conversation_message.conversation_message_id
                        ) desc,
                        (content <> '') desc,
                        conversation_message_sequence desc,
                        id desc
           ) as row_number
      from conversation_message
     where role = 'assistant'
       and execution_id is not null
       and is_deleted = false
)
delete from conversation_message message
 using ranked_assistant ranked
 where message.tenant_id = ranked.tenant_id
   and message.conversation_message_id = ranked.conversation_message_id
   and ranked.row_number > 1;

-- 消息表只保存真实正文和与正文绑定的 thinking_process，不再保留内部消息载荷列。
alter table conversation_message
    drop column tool_calls,
    drop column tool_execution_id,
    drop column tool_call_id,
    drop column tool_name,
    drop column visibility,
    add constraint ck_conversation_message_role
        check (role in ('user', 'assistant', 'system'));

-- 工具执行只关联 assistant 中心消息及其中的 TOOL ProcessItem UUID。
-- 已软删除的历史孤儿行不参与运行时恢复，但仍需满足新列的非空数据库契约。
update tool_execution
   set tool_process_item_id = gen_random_uuid()
 where tool_process_item_id is null
   and is_deleted = true;

alter table tool_execution
    drop column observation_conversation_message_id,
    alter column tool_process_item_id set not null,
    add constraint fk_tool_execution_assistant_center_message
        foreign key (tenant_id, assistant_conversation_message_id)
        references conversation_message(tenant_id, conversation_message_id),
    add constraint ck_tool_execution_terminal_state check (
        (status in ('prepared', 'executing') and completed_at is null)
        or (status in ('succeeded', 'failed') and completed_at is not null)
    );

-- 每次 execution 只允许一条未删除 assistant 中心消息。
create unique index uq_conversation_message_execution_assistant
    on conversation_message (tenant_id, execution_id)
    where role = 'assistant' and is_deleted = false;
