alter table personal_memory_review_job add column blocked_configuration_fingerprint varchar(64);
comment on column personal_memory_review_job.blocked_configuration_fingerprint is '阻塞时模型、纯估算策略、预算及审查限制的安全摘要；为空表示旧任务无可比较配置，仅新完成事件可激活。';
