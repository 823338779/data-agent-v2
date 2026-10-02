alter table git_change add column validation_evidence text;
comment on column git_change.validation_evidence is '与候选快照共同封存的 dbt 校验凭证；历史或非受管候选为空，空值不能冒充验证通过';
