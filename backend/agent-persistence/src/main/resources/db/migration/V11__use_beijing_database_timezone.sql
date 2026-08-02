-- 新建 PostgreSQL 连接默认使用北京时间，使 timestamptz 直接查询时显示东八区偏移。
do $$
begin
    execute format(
        'alter database %I set timezone to %L',
        current_database(),
        'Asia/Shanghai');
end
$$;

-- 当前 Flyway 连接立即采用相同时区，保证本次迁移后的会话行为一致。
set time zone 'Asia/Shanghai';
