alter table agent_execution add column analysis_build_id text;
comment on column agent_execution.analysis_build_id is '本次执行首次固定的dbt构建ID，空值表示尚未进行分析；新预览只影响后续执行';
