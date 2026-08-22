-- 会话列表统一按 conversation_session.updated_at 排序，不再保存用户手工排序位置。
drop index ix_conversation_session_user_order;

alter table conversation_session_user_state
    drop constraint uq_conversation_session_user_sort,
    drop column sort_order;

-- 排序版本只服务已移除的完整重排接口，因此连同用户偏好表一起删除。
drop table conversation_preference;
