-- 当前阶段不维护用户级会话可见性或已读游标，删除对应状态表。
drop table conversation_session_user_state;

-- 未读功能移除后，会话不再维护可通知事件水位；活动时间仍用于摘要排序和收敛。
alter table conversation_session
    drop constraint ck_conversation_session_notifiable_sequence,
    drop column last_notifiable_event_sequence;
