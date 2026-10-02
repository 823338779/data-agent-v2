-- 旧算法会跳过空 assistant 轮次，其 Cold 覆盖与新 user 边界算法不兼容。
-- checkpoint 和 Cold 均为可由原始会话消息重建的派生数据；先删发布指针，再删摘要正文。
delete from conversation_context_hierarchical_checkpoint;
delete from conversation_context_hierarchical_cold;
