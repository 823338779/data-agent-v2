# Agent 与 dbt-service 业务链路验收

验收日期：2026-10-02。本地真实服务、Chrome 页面、现有电商数据源和 Git 仓库；没有使用模拟查询结果。

## 验收结果

通过 Agent 页面新建 `models/e2e_average_order_value.yml`，复用 `revenue / order_count` 定义客单价；完成定义验证、差异审阅、确认推送，再由现有管理命令发布。发布成为活动版本后，在同一会话通过 Agent 查询整体和渠道数据，结果与独立原始订单 SQL 一致。

维护范围为业务指标 YAML，未通过 Agent 修改 SQL 或原始订单数据。正式发布仍由现有管理流程负责。验收指标和对应 Git 提交保留，便于复查。

| 环节 | 最终证据 |
| --- | --- |
| 会话 | `conversation_session_dae1011d2de447439d6e44060795d3be` |
| YAML 验证成功 | `ced75aa7-357a-444b-b027-7a2726481f42` |
| 页面确认推送成功 | `6f33594c24f1b55450bbed17c2485f3558756a78` |
| 活动发布 | `53d1ff35-000d-4ad7-af9f-54c3f9e5e018`，`PUBLISHED`，`FULL_BUILD` |
| 发布任务 | `4d33d31d-8c99-4a1a-9063-c21ac3a7bbf9`，`SUCCEEDED` |
| 整体查询 | `fea1ade8-a3e9-4194-81c4-5784cb204f6d` |
| 渠道查询 | `dde58b94-506b-48f3-9a54-8dc5978e769f` |

查询业务时区为 `Asia/Shanghai`，输入时间为 2026-09-01 00:00:00 至 2026-09-30 23:59:59。当前 MetricFlow 包含结束时间并按时间粒度对齐，不能将下月首日当作排他上界。两个结果均未截断，分别返回 1 行和 3 行，无后续分页。

| 范围 | 成交额 | 去重订单数 | 客单价（显示两位小数） |
| --- | ---: | ---: | ---: |
| 整体 | 276176.04 | 682 | 404.95 |
| App | 145824.32 | 366 | 398.43 |
| MiniProgram | 41970.98 | 94 | 446.50 |
| Web | 88380.74 | 222 | 398.11 |

独立核对以 `ecommerce_raw.orders` 关联 `order_items`，筛选 `PAID` 及九月时间范围，统计去重订单并汇总折后明细金额。订单数和金额完全一致；比率与高精度除法的差异小于 `1e-9`。

## 实测问题与处理

1. 本机 dbt/Git 开关未启用，HTTP Git 地址只读可用但不能推送：在本地控制器覆盖配置中启用能力，并使用已有授权的 SSH 地址访问同一仓库。
2. 多步建模达到默认 12 次模型调用后，已生成卡片但最终回答失败：应用配置改为最多 32 次模型调用，仍保留工具次数和执行时限。
3. Agent 混淆文件列表、搜索、读取和差异接口的限制：在内置 Skill 中分别说明单位与上限。
4. dbt 普通测试默认带 `dbt_test__audit`，受控 schema 宏误拒绝；`store_failures=false` 归一化为 `ephemeral` 又被误判为写入：允许只读测试解析，继续拒绝真实失败结果表或视图写入。
5. dbt 原生 manifest 的空 freshness 阈值对象被误认为已配置规则：仅在 count 和 period 均有值时要求执行 freshness；零阈值仍有效。
6. 整月查询误用下月首日作排他结束时间，多计 11 笔、5455.99：补充数据分析 Skill 的包含结束时间及粒度对齐规则，重启后自然语言重查，结果与原始 SQL 一致。
7. 连续操作触及保守上下文预算：增加明确的预算配置入口；核实本机 Codex 模型声明容量为 272000 后，本机覆盖为 131072。公共默认值仍为 65536，保留近期原文、压缩和硬上限保护；未更改上下文算法。

失败发布任务在确认旧服务进程树全部停止后，通过已有管理命令完成停止确认；未绕过发布验证或租约保护。

## 回归与服务状态

- Agent 最终 Maven 构建：`-pl agent-server -am -Dtest=Dbt*Test,GitSkillTest,React*Test -Dsurefire.failIfNoSpecifiedTests=false package`，33 项通过，无失败或跳过。
- dbt-service：publication build/template/namespace/plan/catalog 范围 42 项通过；新增测试使用实际 dbt parse 复现普通测试 schema 和 store_failures 归一化行为，并覆盖空 freshness 阈值及零阈值。
- 修改文件通过 `git diff --check`；dbt-service 修改文件通过 Ruff。
- dbt-service（8000）、Metric Platform（8090）、Agent（8080）及两个前端（5173、5174）健康检查均为 200，验收后保持运行。
- 页面截图和原始核对结果位于工作区根目录 `tmp/dbt-e2e-query-passed.png`、`tmp/dbt-e2e-verified-results.json`。产品源码尚未提交；既有无关工作区修改保留。

本次覆盖上述电商 YAML 指标维护及查询场景，不代表已经穷举所有 dbt 模型类型、业务粒度或长会话长度。

## 追加循环验收

用户追加授权自动完成按钮点击、差异审阅、确认推送等测试交互后，又执行了两轮业务维护与发布，并对发现的异常修复、重启和重测。正式发布仍通过已有管理命令触发，没有给 Agent 增加直接发布能力。

| 环节 | 第一轮：修改客单价说明 | 第二轮：新增万元成交额 |
| --- | --- | --- |
| 会话 | `conversation_session_5a551f63c2d04007b73b62eda2d75b01` | `conversation_session_0e2e5bf34374482c8452d2c7323c4cc4` |
| 验证 | `5b406fe1-91aa-4e2f-b76a-4b7ad87acaf9` | `1450c038-e85c-4bd5-a8c0-e1e15993922c` |
| 已确认推送提交 | `8c1fed9d0ba0fd749edd83da5acd4480e5a1bcfa` | `3d2bcdbc4386972f2f9db7f1d89940fd82ac3ee7` |
| 发布 | `9f7c1def-fbf7-4d82-8a7f-fd15e54a500c` | `73a24717-35fd-4933-ab30-a6389a638e26` |
| 发布任务 | `7726e0e3-a807-4a44-8ea3-5547e2760e0c` | `6ce1108f-717e-47af-99a6-815ed25cf7ba` |
| 发布模式 | `SEMANTIC_ONLY` | `SEMANTIC_ONLY` |

追加验收结束时，活动发布序号为 **5**，对应第二轮发布。检查证明：仅推送 YAML 不改变活动版本，新指标在发布前不进入活动目录；发布成功后可查询，旧版本目录返回 HTTP 410。

第二轮新增 `e2e_revenue_wan = revenue / 10000.0`。修复后最终联合查询四项指标，整体成交额为 276176.04 元、682 单、27.617604 万元、客单价 404.95020527859236 元/单；渠道万元值为 App 14.582432、Web 8.838074、MiniProgram 4.197098。全部结果与重新读取的原始订单 SQL 核对一致，未截断。

### 循环中新增发现及修复

1. `dbt_resource_get.view` 未向模型声明合法枚举，导致模型传入 `DETAIL`。补齐小写枚举及默认 `detail`，并在 Skill 中说明。先观察契约测试失败，再修复并验证真实 `view=detail` 调用成功。
2. 模型把提交查询的 `limit=1000` 复制到分页读取，超过服务每页 200 行的上限。Agent 读取工具现在接受有界请求并将实际页大小封顶至 200，保持服务返回的真实 `nextOffset`。回归覆盖请求 1000、返回 200、总共 300 行且下一游标为 200，防止跳行；未增大服务响应上限。
3. 用户要求“重新读取”时，模型曾只复述历史结果。将必须真实调用的要求加入加载前可见的 dbt Skill 摘要和完整指引。用完全相同的用户请求重测，执行记录确认实际调用 `skill_read`、`dbt_query_get`，没有 `dbt_query_submit`。

最终重测证据：

- 第一轮最终查询 10 次工具调用全部成功，整体查询 `9b38ffbe-905b-4f65-a0d6-89e4d3705959`，渠道查询 `be7ccda5-e92c-4ca8-ade4-4c50a7cbcaf7`。
- 第二轮最终联合查询 11 次工具调用全部成功，整体查询 `90e037d4-9a8a-4d91-8ef0-527cc8219f1d`，渠道查询 `b1f5ef2e-46a2-4d88-93fd-7895d65ec39f`。
- 分页专项复测请求 `offset=0, limit=1000`，实际服务访问日志为 `limit=200`、HTTP 200，返回 3 行、`nextOffset=null`、`resultTruncated=false`，结果与之前保存的同一查询完全一致。
- 最新 Maven 构建相关测试 **35 项通过，无失败、错误或跳过**。两个新增回归均验证过修复前失败、修复后通过。Java 修改由项目 Spotless 格式化；`git diff --check` 通过。
- 五个服务最后健康检查均为 200，保持运行。产品源码修复未创建提交，业务测试 YAML 已通过页面确认推送并发布；没有修改原始订单数据。

可复查证据位于工作区根目录 `tmp/dbt-loop-round1-results.json`、`tmp/dbt-loop-round2-results.json`、`tmp/dbt-loop-reread.json`，以及 `tmp/dbt-loop-round2.png`、`tmp/dbt-loop-reread-passed.png`。参数契约和读取指引的修复仅经过本记录中的回归与实际模型重测，不等同于对模型所有未来回复的形式化保证。
