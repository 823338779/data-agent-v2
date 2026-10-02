# dbt-service 接入与 YAML 维护设计

日期：2026-10-02

状态：已由联合方案替代；用户确认后按实施计划完成两端接入。

用户已明确允许本次同时修改 data-agent-v2 和 dbt-service。此前的“只改 agent、仅进行本地 YAML 结构检查”方案不再作为实施依据。

跨产品设计已按工作区规则集中到根仓库：

[Data Agent 与 dbt-service 联合接入设计](../../../../docs/superpowers/specs/2026-10-02-agent-dbt-service-integration-design.md)

联合方案维持已确认的发布边界：agent 维护 YAML、展示差异、通过现有卡片确认后推送；发布仍由现有流程触发。

主要调整：

- dbt-service 增加固定 Git 基线、支持相对路径和增改删的异步草稿语义验证。
- agent 持久化验证证据，生成 Git 候选时核对确切内容，避免校验后编辑造成证据失效。
- dbt-service 补充结构化错误、异步查询选项、状态与结果分页，并统一时间契约。
- 保留 metricPlatform 现有接口兼容性，两端独立构建，不跨仓库引用实现。

产品配置、能力边界和验证命令见 [README](../../../README.md#dbt-service-接入)。功能默认关闭，发布继续使用原流程。
