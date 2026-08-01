-- Run stream 的发布序号只在当前 attempt/turn 内单调增长，queued 投影保持全零坐标。
alter table agent_run_stream
    add constraint ck_agent_run_stream_sequence check (stream_sequence >= 0),
    add constraint ck_agent_run_stream_coordinate check (
        (execution_attempt = 0 and model_turn_sequence = 0 and stream_sequence = 0)
        or (execution_attempt >= 1 and model_turn_sequence >= 1)
    );

-- Reconciliation 按会话定位当前 checkpoint，避免跨实例恢复时扫描无关 Run。
create index ix_agent_run_stream_checkpoint
    on agent_run_stream (tenant_id, checkpointed_at, run_id)
    where is_deleted = false;
