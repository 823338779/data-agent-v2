-- 候选目标独立封存；历史 main 候选保留原字段与快照格式。
alter table git_change add column project_id varchar(128);
alter table git_change add column branch_id varchar(128);
alter table git_change add column git_ref varchar(512);
comment on column git_change.project_id is '候选固定的 dbt 项目；历史 main 候选为空';
comment on column git_change.branch_id is '候选固定的分支实例；历史 main 候选为空';
comment on column git_change.git_ref is '候选固定的完整远端引用；历史 main 候选为空并按旧协议恢复';

alter table dbt_validation add column branch_id varchar(128);
alter table dbt_validation add column git_ref varchar(512);
alter table dbt_validation add column config_version varchar(256);
alter table dbt_validation add column toolchain_version varchar(256);
comment on column dbt_validation.branch_id is '验证受理时固定的分支实例；历史线上验证为空';
comment on column dbt_validation.git_ref is '验证受理时固定的完整 Git 引用';
comment on column dbt_validation.config_version is '受理能力查询观察到的配置版本，变更后需要新验证';
comment on column dbt_validation.toolchain_version is '受理能力查询观察到的工具链版本，变更后需要新验证';
