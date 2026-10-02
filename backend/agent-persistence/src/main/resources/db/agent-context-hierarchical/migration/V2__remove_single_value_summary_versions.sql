-- 这两个版本在该策略中均被约束为固定值 2，不能表达记录差异。
-- 策略整体由 policyVersion 标识；摘要文档本身仍由 V2 schema 校验器严格校验。
alter table conversation_context_hierarchical_cold
    drop constraint ck_hierarchical_cold_versions;

alter table conversation_context_hierarchical_cold
    drop column summary_schema_version,
    drop column summary_prompt_version;
