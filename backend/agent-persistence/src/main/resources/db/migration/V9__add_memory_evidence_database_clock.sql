-- 先保留历史行的未知时间，再给以后新增的消息设置数据库默认值，禁止迁移时把旧消息伪装成新证据。
alter table conversation_message add column memory_evidence_created_at timestamp;
alter table conversation_message alter column memory_evidence_created_at
    set default (clock_timestamp() at time zone 'Asia/Shanghai');
comment on column conversation_message.memory_evidence_created_at is '数据库生成的记忆证据创建时间（北京时间）；独立于应用审计时钟且业务代码只读。迁移前历史消息为空，不得作为记忆写入授权，不回填应用时间。';
