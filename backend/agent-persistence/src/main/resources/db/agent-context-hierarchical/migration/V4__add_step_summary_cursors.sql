-- 保留全部 V2 原文及轮次字段；首次 V3 成功发布时才写入新游标。
alter table conversation_context_hierarchical_checkpoint
    add column covered_through_json text,
    add column evaluated_through_json text,
    add column policy_version integer not null default 2,
    add column schema_version integer not null default 2,
    add constraint ck_hierarchical_step_checkpoint check (
        (schema_version = 2 and policy_version = 2 and covered_through_json is null and evaluated_through_json is null)
        or (schema_version = 3 and policy_version = 3 and covered_through_json is not null and evaluated_through_json is not null)
    );
comment on column conversation_context_hierarchical_checkpoint.covered_through_json is 'V3 已覆盖步骤游标；V2 为空';
comment on column conversation_context_hierarchical_checkpoint.evaluated_through_json is 'V3 已观察游标，不能替代覆盖游标';
comment on column conversation_context_hierarchical_checkpoint.policy_version is '发布时使用的策略版本';
comment on column conversation_context_hierarchical_checkpoint.schema_version is '当前活动摘要的协议版本';

alter table conversation_context_hierarchical_cold
    add column schema_version integer not null default 2,
    add column step_coverage_json text,
    add constraint ck_hierarchical_step_cold check (
        (schema_version = 2 and step_coverage_json is null)
        or (schema_version = 3 and step_coverage_json is not null)
    );
comment on column conversation_context_hierarchical_cold.schema_version is '不可变摘要协议版本';
comment on column conversation_context_hierarchical_cold.step_coverage_json is 'V3 左开右闭范围及真实来源摘要，V2 为空';
