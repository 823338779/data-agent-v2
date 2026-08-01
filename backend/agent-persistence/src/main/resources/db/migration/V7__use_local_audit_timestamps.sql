-- 将既有审计时间按北京时间保留为无时区本地时间，并统一后续数据库默认值。
do $$
declare
    audited_table text;
begin
    foreach audited_table in array array[
        'tenant',
        'user_account',
        'tenant_membership',
        'agent_session',
        'agent_message',
        'agent_run',
        'agent_run_event',
        'agent_run_stream',
        'notification_outbox',
        'agent_run_attempt',
        'agent_model_turn',
        'agent_tool_execution'
    ]
    loop
        -- 先移除依赖原字段类型的默认值，再执行显式时区转换。
        execute format(
            'alter table %I
                alter column created_at drop default,
                alter column updated_at drop default',
            audited_table);
        execute format(
            'alter table %I
                alter column created_at type timestamp without time zone
                    using created_at at time zone %L,
                alter column updated_at type timestamp without time zone
                    using updated_at at time zone %L,
                alter column deleted_at type timestamp without time zone
                    using deleted_at at time zone %L',
            audited_table,
            'Asia/Shanghai',
            'Asia/Shanghai',
            'Asia/Shanghai');

        -- 数据库直写场景同样使用本地时间，应用写入仍由 LocalDateTime.now() 填充。
        execute format(
            'alter table %I
                alter column created_at set default localtimestamp,
                alter column updated_at set default localtimestamp',
            audited_table);
    end loop;
end
$$;
