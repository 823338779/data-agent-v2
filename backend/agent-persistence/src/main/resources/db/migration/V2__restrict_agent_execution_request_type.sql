alter table agent_execution
    drop constraint ck_agent_execution_request_type;

-- 历史库可能保留删除功能前创建的 execution_retry 记录。NOT VALID 跳过旧数据校验，
-- 但仍会拒绝后续写入 execution_retry，在保留审计事实的同时收紧新 execution 的边界。
alter table agent_execution
    add constraint ck_agent_execution_request_type
    check (request_type = 'message_submission') not valid;

comment on column agent_execution.request_type is '幂等请求类型；当前仅支持消息提交，历史记录可能保留 execution_retry。';
