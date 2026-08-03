-- reasoning 不再由模型生成、传输或展示，历史数据在删除列前直接丢弃。
update agent_execution
   set phase = 'ANSWERING'
 where phase = 'THINKING';

update agent_execution_stream_checkpoint
   set phase = 'ANSWERING'
 where phase = 'THINKING';

alter table agent_execution drop constraint ck_agent_execution_phase;
alter table agent_execution add constraint ck_agent_execution_phase check (
    phase in ('QUEUED', 'RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL',
              'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
);

alter table agent_execution_stream_checkpoint
    drop constraint ck_agent_execution_stream_checkpoint_phase;
alter table agent_execution_stream_checkpoint
    add constraint ck_agent_execution_stream_checkpoint_phase check (
        phase in ('QUEUED', 'RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL',
                  'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
    ),
    drop column reasoning,
    drop column reasoning_expires_at;

alter table model_invocation
    drop column reasoning,
    drop column reasoning_expires_at;
