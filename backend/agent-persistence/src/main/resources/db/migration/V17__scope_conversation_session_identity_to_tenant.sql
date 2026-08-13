-- 会话业务标识属于租户命名空间；保留既有租户复合唯一约束作为所有外键和隔离边界。
alter table conversation_session
    drop constraint uq_conversation_session_conversation_session_id;
