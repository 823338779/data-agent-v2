alter table conversation_context_hierarchical_compaction_job
    add column candidate_cold_document jsonb;

comment on column conversation_context_hierarchical_compaction_job.candidate_cold_document is
    'GENERATING job 私有持有的 durable Cold 候选正文、可信 coverage、token 和生成身份。';
