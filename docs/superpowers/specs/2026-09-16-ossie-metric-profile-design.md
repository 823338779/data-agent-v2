# 基于 Ossie 的指标定义精简协议设计

日期：2026-09-16

状态：已根据讨论整理为本地设计文档；本次仅记录协议设计，不启动实现。

能力基准：当前仓库 `agent-data-source` 的语义目录和 `agent-workflow` 的查询流程。若实际消费者是另一套独立指标平台，需根据其模型重新核对映射；本次没有获得独立平台接口或生产元数据。

## 1. 结论与方案选择

建议采用 **Data Agent Ossie Profile v1**：保留 Ossie 的对象结构，限制允许的字段和表达式，以标准扩展机制补充当前平台的少量元数据。Profile 指双方约定的规范子集，不是 Apache 官方发布的新版本。

比较了三个方向：

| 方案 | 优点 | 代价与结论 |
|---|---|---|
| Ossie 子集 + 少量标准扩展 | 文档结构兼容上游，能力边界可校验，能映射现有目录 | 需要表达式解析和消费适配；推荐 |
| 完整 Ossie | 可表达复杂计算与多数据集指标 | 远超当前能力，包括任意计算字段、窗口、多方言等；本次不采用 |
| 现有 YAML 改成类似 Ossie 的字段名 | 接入成本较低 | 容易成为不能通过上游 Schema 的私有格式；不采用 |

同一份 YAML 是 Agent 生成、用户审阅、Git 保存和指标平台消费的权威定义。内部可以转换为 Java 领域对象，但不产生第二份需要人工维护的指标定义。

本次仅设计定义协议。沿用已确认的 Git 工作流：卡片确认后 commit + push main，Agent 在确认 push 成功后结束；平台订阅、重试和生效状态由平台自行负责。

### 1.1 核心对象与职责

```text
semantic_model：完整业务语义模型
├── datasets：数据集，可表示事实表或维度表
│   ├── source：已经存在的物理表或视图
│   ├── primary_key：唯一键声明
│   └── fields：业务字段及其物理列映射
│       ├── dimension.is_time = false：普通维度
│       ├── dimension.is_time = true：时间维度
│       └── 不声明 dimension：本 Profile 映射为度量字段
├── relationships：数据集之间的关联
├── metrics：具有确定计算口径的指标
└── custom_extensions：标准扩展容器，承载本项目补充元数据
```

度量字段与指标不同：`payments.amount` 是金额字段，`paid_revenue` 是对已支付交易金额求和的指标。数据集负责来源，字段负责映射及分析角色，指标负责计算，关系负责跨数据集取维度。

定义协议不包含本次查询的起止日期、按天或按月分组、排序和返回条数。这些属于 QueryPlan。例如“上个月按地区统计每天的支付收入”，指标公式来自 metrics，地区与时间字段来自 datasets，关联来自 relationships，上个月和按天来自查询请求。

### 1.2 维度的表达边界

当前设计没有顶层 `dimensions` 集合，也不引入脱离数据集的公共维度对象。

| 用户概念 | 本协议如何表达 | 边界 |
|---|---|---|
| 支付状态维度 | `payments.state` 字段及 dimension 属性 | 属于支付数据集 |
| 支付时间维度 | `payments.paid_at`，is_time 为 true | 数据集扩展可以指定它为默认时间字段 |
| 可复用的地区维度表 | `regions` 数据集及其字段 | 其他事实数据集通过 relationships 关联它 |
| 独立业务概念“地区”及多来源映射 | 当前不支持 | 不能把维度表自动当作无物理绑定的公共维度对象 |
| 省市区层级、编码显示值映射、年龄段计算维度 | 当前不支持 | 不能仅写入 description 并要求模型猜测执行规则 |

把一段 YAML 拆成独立文件不会使其成为独立维度对象；首版也没有跨文件引用机制。若后续需要单独创建、维护和复用公共维度，须另行设计其标识、来源映射和引用语义，不能直接增加非标准顶层 dimensions 并仍声称是本协议。

定义数据集或维度只登记元数据，不创建物理表、不维护维度表数据行，也不生成物化结果。表或视图需要已经存在。

## 2. 上游规范基线

截至 2026-09-16 的调研发现：

- Ossie 主分支当前为 `0.2.0.dev0`，上游明确标记未发布、Schema 可变，不适合直接作为生产契约。
- 本次核实到 `osi-0.1.1-rc1` 标签，指向提交 `faf581054dcf7964d5fe0ceae7d6f415c8ce32a5`；该提交 Schema 中的文档版本为 `0.1.1`。GitHub Releases 页面尚无 release，不能把这个 rc 标签称为正式发布制品。
- 推荐以该固定提交的 `core-spec/osi-schema.json` 作为候选基线，文档写 `version: "0.1.1"`。实施时随契约分发固定 Schema，不能运行时从 main 下载。
- `0.1.1` 中 field、metric 没有标准 `datatype` 属性；类型由已配置的数据库元数据提供。本次不沿用主分支新字段。
- 该 Schema 的 `vendor_name` 为枚举，没有 `DATA_AGENT`；扩展使用已有值 `COMMON`，在 JSON 内容内定义 `data_agent` 命名空间。
- 扩展的 `data` 是 **JSON 字符串**，即使外层用 YAML，也不能直接改为 YAML 对象。

协议版本分开管理：外层 `version` 表示上游结构版本；模型扩展中的 `profile_version: 1` 表示本项目限制和消费语义版本；Git commit 表示某次业务内容快照。三者不能互相替代。

### 2.1 原生字段、项目扩展与项目限制

| 层次 | 内容 | 来源 |
|---|---|---|
| 文档结构 | version、semantic_model | Ossie 原生 |
| 模型内容 | name、description、datasets、relationships、metrics | Ossie 原生 |
| 数据集内容 | name、source、primary_key、fields | Ossie 原生 |
| 字段与指标 | name、expression、description | Ossie 原生；dimension 只属于字段 |
| 维度属性 | dimension.is_time | Ossie 原生 |
| 表达式结构 | expression.dialects[].dialect / expression | Ossie 原生 |
| 关联属性 | from、to、from_columns、to_columns | Ossie 原生 |
| AI 上下文 | ai_context.synonyms | Ossie 原生，允许出现的位置见第 5 节 |
| 扩展容器与枚举值 | custom_extensions、vendor_name、data、COMMON | Ossie 原生 |
| 扩展内容 | data_agent、profile、profile_version、display_name、unit、default_time_field、join_type | 本项目自行定义，不是 Ossie 标准业务字段 |
| 消费限制 | 单模型、单数据源、六种聚合、单字段关联、ANSI_SQL、字段角色默认映射 | 本 Profile 的规则，不是 Ossie 标准要求 |

因此，对外应称“基于 Ossie 的受限协议，附带 Data Agent 平台扩展”，不能称“所有字段和执行语义均为 Ossie 标准”。这些原生字段也不是在任意对象上都能使用，必须遵守 Schema 的宿主位置。

上游结构校验通过，仅说明标准部分及扩展容器的形状正确。其他平台可能忽略 data_agent 中的显示名、默认时间、关联方式等内容；不理解本项目扩展时不能承诺完整语义互通。上游 Schema 通过、本 Profile 校验通过、数据库环境可用、执行口径正确是四个不同层次。

## 3. 当前代码能力与实际边界

| 当前能力 | 代码证据 | 对协议的影响 |
|---|---|---|
| 单个固定 StarRocks 数据源、表和视图、物理字段核对 | `StarRocksMetadataReader` | 不在定义中选择连接或跨数据源查询 |
| 数据集、字段、中文名称、说明、同义词、默认时间字段 | `SemanticDataset`、`SemanticField` | 保留语义元数据，补充少量扩展 |
| DIMENSION / TIME / MEASURE | `SemanticField.Role` | 通过 dimension 属性映射，不再另写 role |
| SUM / COUNT / COUNT_DISTINCT / AVG / MIN / MAX | `SemanticMetric.Aggregation` | 每个指标只允许一个数据集、一个基础聚合 |
| 固定过滤可存储字段、操作符和值 | `SemanticMetric.MetricFilter` | 能映射结构，但现有加载器未严格校验操作符和值类型 |
| 查询过滤有十种操作符及值数量校验 | `QueryFilter`、`QueryPlanValidator` | 可复用其语义，不等于指标过滤已完成同样校验 |
| 单字段等值关联，模型存储 INNER / LEFT 和 cardinality | `SemanticRelationship` | 首版只表达单字段关联，不表达复合关联 |
| SQL 表列和关联字段白名单、只读校验 | `StarRocksSqlPolicyValidator` | 不是完整指标口径校验；也未强制 JOIN 类型与基数 |
| 启动时加载不可变语义目录 | `StarRocksDataSourceConfiguration`、`SemanticResourceSearchService` | Git 热更新尚需消费者改造，协议文件本身不会带来热更新 |

特别限制：当前流程由模型生成 SQL，不能据此宣称已经严格执行指标聚合、固定过滤和关联基数。以下是拟定的消费契约，不是已实现功能的说明。

## 4. 文档与仓库组织

首版固定读取 `semantic/model.yaml`，一个文件包含恰好一个完整 semantic_model，可包含多个 dataset、metric 和 relationship。使用单文件避免当前阶段引入跨文件引用、合并优先级和部分覆盖语义。

- UTF-8、单个 YAML 文档，按 YAML 1.2 的 JSON 兼容数据类型解析。
- 禁止重复键、对象标签、锚点、别名和 merge key；日期文字显式加引号。
- 未列入本 Profile 的属性一律拒绝；可选对象缺省时使用本文默认值，显式 null 不代替缺省。
- 名称使用 `[a-z][a-z0-9_]*`，是稳定标识；显示名称变更不修改标识。
- dataset、metric、relationship 的名称跨三类不得重复；field 名称仅要求在所属 dataset 内唯一。
- 数组顺序不改变计算语义。数组删除、对象删除均通过完整快照表达，不额外定义 delete 指令。
- model 文件缺失、空模型、悬空引用导致整个快照拒绝；允许 `metrics: []`，用于明确清空指标，同时仍须保留至少一个合法 dataset。
- 文件大小等传输限制沿用 Git 设计，不在协议中另行定义一套冲突限制。

平台绑定固定模型名，后续提交改变模型名视为不兼容变更，首版拒绝。对象 name 改名按删除旧对象并新增处理；显示名改动是原对象更新。

## 5. 字段白名单与裁剪

| 对象 | 保留字段 | Profile 限制 |
|---|---|---|
| 根 | version、semantic_model | version 固定 0.1.1；恰好一个模型 |
| 模型 | name、description、datasets、metrics、relationships、custom_extensions | datasets 非空；metrics 与 relationships 可空；必须通过扩展声明 Profile 版本 |
| 数据集 | name、source、description、fields、primary_key、custom_extensions、ai_context | fields 非空；source 只允许当前固定数据库下的表或视图名；primary_key 可选且仅一个列 |
| 字段 | name、expression、dimension、description、ai_context、custom_extensions | 只允许一个物理列引用，不能是计算表达式 |
| 关系 | name、from、to、from_columns、to_columns、custom_extensions | 两侧列数组各一个元素；from 是多侧、to 是一侧 |
| 指标 | name、expression、description、ai_context、custom_extensions | 限制为第 6 节的表达式 |
| AI 上下文 | synonyms | 仅接受对象及字符串数组，不接受 instructions、examples 或字符串简写 |
| 表达式 | dialects 中的 dialect、expression | 恰好一个方言，固定 ANSI_SQL |

必填与默认值：

- 根的 version、semantic_model 必填；模型 name、datasets 和 Profile 版本扩展必填。
- 数据集 name、source、fields 必填；字段 name、expression 必填。
- 指标 name、expression 必填；关系 name、from、to、from_columns、to_columns 和 join_type 扩展必填。
- metrics、relationships 缺省时按空集合解释；Agent 生成文件时显式写出这两个集合，便于审阅清空行为。
- primary_key 可省略；如果数据集是关联的 to 侧，则按第 7 节必须提供单列唯一键声明。
- description、ai_context、展示扩展可省略；缺少同义词视为空集合，缺少显示名使用 name，缺少 unit 表示未声明。
- 未指定 default_time_field 时不自动选择多个 TIME 字段中的任意一个，查询计划需要明确指定时间字段。

source 例：`fact_payment`。连接、catalog 和 database 来自部署配置，定义不能修改。首版拒绝 SQL source、带限定符的 source 和需要引号的复杂标识；未来再按版本扩展。消费端匹配真实元数据，不应只截取任意路径的最后一段。

字段表达式例：`paid_amount`。字段逻辑名可不同于物理列名，但同一个数据集内，一个物理列只能声明一个 field，保证关系列到逻辑字段的映射唯一。

字段角色的本项目解释：`dimension: {is_time: true}` 为 TIME；`dimension: {is_time: false}` 为 DIMENSION；省略 dimension 为 MEASURE。若 dimension 存在，is_time 必填，不采用新版的类型推断默认值。TIME 首版仅允许数据库日期或时间戳类型；不把整型年份自动当作时间轴。

暂不支持：多方言、任意计算字段、跨数据集指标、指标引用指标、比率、同比环比、窗口函数、累计指标、子查询、宏、模板、复合主键、复合关联、unique_keys、多对多关联、AI 执行指令和其他厂商扩展。

## 6. 指标计算与固定过滤

### 6.1 唯一计算来源

指标计算只由 `expression` 决定，不重复维护 aggregation、field、filters。消费适配器从 AST 确定性解析为内部结构，不调用大模型解释协议，也不使用正则替换代替语法校验。

允许以下形式，其中 d.f 为 dataset 名与 field 逻辑名，不是物理表列：

```text
SUM(d.f)                 AVG(d.f)
MIN(d.f)                 MAX(d.f)
COUNT(d.f)               COUNT(DISTINCT d.f)

SUM(CASE WHEN P THEN d.f END)
AVG(CASE WHEN P THEN d.f END)
MIN(CASE WHEN P THEN d.f END)
MAX(CASE WHEN P THEN d.f END)
COUNT(CASE WHEN P THEN d.f END)
COUNT(DISTINCT CASE WHEN P THEN d.f END)
```

表达式必须完整解析为一个聚合节点；禁止额外语句、注释、任意标识符引用和解析后的剩余文本。SQL 关键字大小写不影响含义，Agent 统一生成大写关键字；逻辑标识遵循第 4 节的小写约束。

COUNT 统计非 NULL 字段，COUNT DISTINCT 对非 NULL 值去重；首版不接受 COUNT(*) 或 COUNT(1)，避免引入现有模型没有的无字段指标。统计订单数应选择非空订单标识字段。

SUM、AVG 要求数值字段；MIN、MAX 要求可比较的标量字段；COUNT 类要求可计数的标量字段。数组、对象等复杂类型首版拒绝。缺少满足条件的非空值时，COUNT 返回 0，其他聚合保留 SQL 的 NULL 语义，不隐式补零。

### 6.2 过滤语法

P 只允许同一数据集字段与字面量的比较，多个条件只能 AND 连接。允许括号改变分组书写，但不扩充逻辑能力。

| SQL 形式 | 内部操作符 | 值约束 |
|---|---|---|
| =、<>、>、>=、<、<= | EQ、NE、GT、GTE、LT、LTE | 一个非 NULL 标量 |
| IN、NOT IN | IN、NOT_IN | 非空同类型标量列表，元素不为 NULL |
| IS NULL、IS NOT NULL | IS_NULL、IS_NOT_NULL | 无值 |

不支持 OR、NOT 布尔组、LIKE、BETWEEN、函数、字段间比较、子查询或动态当前时间。条件内每个字段都必须已声明，字面量必须与数据库字段类型匹配，不依赖数据库隐式数值/字符串转换。日期或时间戳使用显式带引号的 ISO 文字，由类型校验器解释，时区语义沿用该固定数据源的部署约定。

CASE 只允许一个 WHEN，THEN 必须是聚合字段，ELSE 必须省略；省略 ELSE 表示不匹配行提供 NULL。禁止 ELSE 0，尤其不能让它改变 AVG 的分母。

例：`SUM(CASE WHEN payments.state = 'PAID' THEN payments.amount END)` 表示只统计已支付金额。固定过滤直接进入标准 SQL 表达式，避免其他读取者忽略私有 filters 扩展后扩大指标范围。

消费时必须保留“指标内条件聚合”的语义。不能把每个指标的条件统一拼进查询 WHERE，否则两个不同过滤口径的指标会相互影响；也可能让原本应返回 NULL/0 的分组消失。用户查询过滤与指标固定过滤分别生效，用户过滤不能覆盖固定过滤。

## 7. 最小扩展

所有扩展对象使用 `vendor_name: COMMON`，data 解码后恰好包含 `data_agent`。每个宿主对象最多一项扩展，不接受其他命名空间；JSON 字符串内部同样拒绝重复键和未知属性。

| 宿主 | data_agent 内字段 | 语义 |
|---|---|---|
| 模型 | profile、profile_version | 必填，固定为 ossie-metrics 和 1 |
| 数据集 | display_name、default_time_field | 显示名可选，缺省使用 name；时间字段可选且必须指向本数据集 TIME 字段 |
| 字段 | display_name、unit | 显示名可选，缺省使用 name；单位可选 |
| 指标 | display_name、unit | 显示名可选，缺省使用 name；单位可选 |
| 关系 | join_type | 必填，INNER 或 LEFT；from 对应 SQL 左侧，to 对应右侧 |

unit 是展示元数据，缺省表示未声明；不触发汇率、金额缩放或单位换算。不能把 unit 作为公式的一部分。

没有把私有字段直接塞入 Ossie 对象，也不借用描述或 synonyms 储存执行参数。本项目扩展的 JSON 格式需单独校验，上游 Schema 只会确认 data 是字符串。

关系只接纳多对一方向；若源关系为一对多，需要反转后表达。to_columns 必须等于目标数据集声明的单列 primary_key。这里的 primary_key 是唯一性声明，不代表数据库已经强制唯一。当前元数据读取器没有唯一性证据；消费者需要在允许关联参与指标查询前核实该约束，否则拒绝相关查询，不能承诺能防止重复聚合。首版也不承诺多事实表指标联算。

## 8. 完整示例

以下为本项目原创示例，包含支付事实数据集、地区维度数据集、普通维度、时间维度、关联和带固定过滤的指标。表和列用于说明，未核对真实数据库；可作为后续契约测试夹具。

```yaml
version: "0.1.1"
semantic_model:
  - name: payment_analytics
    description: 支付交易分析
    custom_extensions:
      - vendor_name: COMMON
        data: '{"data_agent":{"profile":"ossie-metrics","profile_version":1}}'
    datasets:
      - name: payments
        source: fact_payment
        description: 支付交易明细
        ai_context:
          synonyms: [支付记录, 交易明细]
        custom_extensions:
          - vendor_name: COMMON
            data: '{"data_agent":{"display_name":"支付交易","default_time_field":"paid_at"}}'
        fields:
          - name: amount
            description: 以元为单位的实付金额
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: paid_amount
            custom_extensions:
              - vendor_name: COMMON
                data: '{"data_agent":{"display_name":"实付金额","unit":"CNY"}}'
          - name: state
            description: 支付状态
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: payment_state
            dimension:
              is_time: false
          - name: paid_at
            description: 支付成功时间
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: payment_time
            dimension:
              is_time: true
          - name: region
            description: 支付交易所属地区编码
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: region_code
            dimension:
              is_time: false
      - name: regions
        source: dim_region
        description: 每个地区编码对应一条地区记录
        primary_key: [region_code]
        custom_extensions:
          - vendor_name: COMMON
            data: '{"data_agent":{"display_name":"地区"}}'
        fields:
          - name: code
            description: 地区唯一编码
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: region_code
            dimension:
              is_time: false
          - name: region_name
            description: 地区显示名称
            expression:
              dialects:
                - dialect: ANSI_SQL
                  expression: region_name
            dimension:
              is_time: false
            ai_context:
              synonyms: [地区名称, 区域名称]
    relationships:
      - name: payments_to_regions
        from: payments
        to: regions
        from_columns: [region_code]
        to_columns: [region_code]
        custom_extensions:
          - vendor_name: COMMON
            data: '{"data_agent":{"join_type":"LEFT"}}'
    metrics:
      - name: paid_revenue
        description: 已支付交易的实付金额合计
        expression:
          dialects:
            - dialect: ANSI_SQL
              expression: "SUM(CASE WHEN payments.state = 'PAID' THEN payments.amount END)"
        ai_context:
          synonyms: [实收金额, 支付销售额]
        custom_extensions:
          - vendor_name: COMMON
            data: '{"data_agent":{"display_name":"支付收入","unit":"CNY"}}'
```

此例中 `payments.region` 是事实表内的地区编码字段，`regions` 是共享维度数据集，`regions.region_name` 是用于分组或展示的地区名称字段。三者职责不同，没有额外的全局 region 维度实体。

## 9. 到现有领域模型的映射

| 协议 | 现有领域对象 |
|---|---|
| dataset.name / source | SemanticDataset.id / table |
| dataset 扩展的 display_name | SemanticDataset.name |
| dataset.default_time_field 扩展 | 转换后的默认字段 ID |
| field.name / expression 中的列 | SemanticField.id / column（ID 需限定数据集） |
| dimension 属性 | SemanticField.Role |
| 数据库元数据类型 | SemanticField.dataType |
| metric.name / AST | SemanticMetric.id / dataset、aggregation、field、filters |
| COUNT(DISTINCT ...) | COUNT_DISTINCT |
| description / synonyms / 扩展 unit | 对应说明、同义词和单位 |
| from、to / 两侧物理列 | 关系两侧数据集 / 对应的内部字段 ID |
| 关系扩展 join_type | INNER 或 LEFT |

当前 QueryPlanValidator 对不同数据集中的同名 field 会判为歧义。适配后字段内部 ID 统一为 `dataset.field`，例如 `payments.amount`；同时同步映射 metric.field、filter.field、defaultTimeField 和 relationship 的字段引用。线上查询计划和工具结果应始终使用适配后的 ID，不能只改字段 ID 而保留旧引用。

关系中的 from_columns / to_columns 在本 Profile 中指物理列，先匹配各数据集已声明的字段表达式，再转换为限定的逻辑字段 ID；指标表达式使用逻辑字段，不能混用。

这些映射是拟增加的消费适配能力。当前 SemanticCatalogLoader 只识别原有 version=1 格式，不能直接加载示例。

## 10. 双方校验和消费契约

双方共用固定版本的：上游 Schema、本 Profile 的结构约束、扩展 JSON 约束、表达式规则和正反例夹具。不同语言实现也必须通过同一套夹具，不能只共享一份文字说明。

校验分层：

1. 解析：YAML/JSON 类型、重复键、单文档规则。
2. 结构：先上游 Schema，再 Profile 字段白名单和数量约束。
3. 语义：名称、引用、表达式 AST、单数据集限制、扩展 JSON 和版本。
4. 环境：平台核对实际表列、类型及关联唯一性等执行前提。Agent 本地可做前三级；数据库信息缺失时不能宣称已完成环境验证。

错误至少包含 `code`、`file`、`path`、`message`，能够定位具体指标或字段。示例代码：UNSUPPORTED_PROFILE_VERSION、UNKNOWN_PROPERTY、INVALID_EXTENSION、INVALID_REFERENCE、UNSUPPORTED_EXPRESSION、TYPE_MISMATCH、SOURCE_NOT_FOUND。未知版本、未知字段和不支持的表达式均拒绝整个快照，不静默丢弃。

错误对象是本项目的校验结果格式，不是 Ossie 原生字段，也不写回定义文件。例如：

```json
{
  "code": "INVALID_REFERENCE",
  "file": "semantic/model.yaml",
  "path": "/semantic_model/0/metrics/0/expression/dialects/0/expression",
  "message": "指标引用了未声明的字段 payments.unknown_amount"
}
```

path 使用 JSON Pointer 定位解析后的文档节点。平台自己的请求 ID、接收时间、active commit 等属于传输或运行时状态，不混入这个定义协议。

Skill 负责教 Agent 写这套格式；确定性校验不能仅靠 Skill。未来应在指标发布流程中对被冻结候选快照做共享契约校验，避免校验后又改文件。通用 agent-git 模块继续负责 Git，不把 Ossie 解析硬编码进通用文件工具。此接入点属于后续实施设计，本次未修改已有 Git 方案。

平台每次消费一个固定 commit 的完整文件，解析和验证成功后构造完整不可变目录，再原子替换活动目录；任一错误保留旧目录。已开始的查询固定使用旧快照，新查询使用新快照，避免一条执行链路混用两套定义。

同一快照重复通知应幂等；旧任务不能覆盖新活动版本。校验规则升级时可对相同 commit 重新验证。hook 只通知有变更，不携带权威指标对象；平台实际拉取的固定 Git 快照才是权威内容。此处定义消费效果，重试调度和回调接口另行设计。

Git 内只保存定义与静态依赖，不写运行状态、任务日志、SQL 查询结果或平台生效状态。由模型、字段、关系能推导静态依赖；查询实际执行链路属于平台运行时能力。

### 10.1 与现有 Agent Git 设计的关系

本设计补充“仓库文件的业务含义”，沿用 [Agent Git 仓库维护与确认提交设计](2026-09-16-agent-git-design.md) 的职责分工：共用远端仓库，各会话独立本地工作区，固定 main，卡片确认绑定候选快照，服务端负责 commit/push。

本轮不改变本地 agent-git 的通用工具接口，不引入 MCP、不将指标平台生效纳入 Agent 成功条件。协议校验接入、平台 Git 通知接收、热更新与查询执行约束属于后续实施工作，不能将此文档理解为这些能力已上线。

### 10.2 版本演进与变更语义

- 业务新增、删除、重命名或修改口径，通过新的 Git commit 表达；不改 profile_version。
- 新增受支持字段、表达式或改变默认语义，需要发布新的 Profile 契约版本，并同步生产者、消费者及测试夹具；不能在版本 1 下静默改变语义。
- 上游规范升级需单独评审，固定新的上游 Schema 提交，不能只修改 YAML 的 version 字符串。
- 生产者生成消费者未知的 Profile 版本时，消费者拒绝整个快照并保留旧版本。上线顺序须先确保消费端具备对应能力。
- 回退定义通过新的 Git 提交恢复原内容；平台消费该新快照，不要求强制推送或改写远端历史。

## 11. 实施前必须明确的验收边界

本轮验证范围：第 8 节完整示例的 YAML 解析、六项扩展 data 的 JSON 解析、固定提交上游 JSON Schema（Draft 2020-12）校验，以及示例字段映射、默认时间和关联引用的一致性。未验证真实数据库和完整 Profile 语义，后两者没有现成实现。本轮只维护本文档，不运行 Maven，不改动运行时代码。

- 示例通过固定上游 Schema；完整 Profile 还需结构与语义验证器，不能把上游 Schema 通过等同于业务可用。
- 原版完整 Ossie 文档中超出子集的内容明确报错，而不是自动降级。
- 同字段映射、中文显示名、默认时间、单位、十种过滤、六种聚合均可确定性映射。
- 验证 CASE 的 NULL 语义，以及两个不同固定过滤指标同时查询时不互相干扰。
- 验证同名字段来自不同数据集时不冲突，关联不能使用未声明列。
- 验证错误快照不会部分生效，重复通知不会重复发布，执行中查询不会跨快照。
- 真正用于指标查询前，还需补齐公式/固定过滤/JOIN 类型的确定性执行约束与关联唯一性校验；这是现有能力缺口，不属于更换文件格式自动解决的问题。
- 本轮不实现 Schema、解析器、数据库适配、热更新或查询编译器；设计认可后分别确定实施范围。

后续共享契约夹具至少覆盖：

| 场景 | 预期 |
|---|---|
| 第 8 节完整示例 | 结构与引用合法，环境检查另行执行 |
| 字段名称不同于物理列名 | 正确建立映射，指标引用逻辑名，关联引用物理列 |
| 添加非标准顶层 dimensions 或 metric.unit | 拒绝，不自动移动到扩展区 |
| 自定义扩展 vendor_name 写为 DATA_AGENT | 按固定上游 Schema 拒绝 |
| 扩展 data 使用 YAML 对象或 JSON 中含未知字段 | 拒绝 |
| 增加 datatype、窗口函数、跨数据集公式或 OR 过滤 | 按本 Profile 拒绝 |
| 两个指标使用不同固定过滤 | 保持各自条件聚合，不合并为全局过滤 |
| CASE 使用 ELSE 0、COUNT(*)、COUNT(1) | 按本 Profile 拒绝 |
| 删除仍被引用的字段或数据集 | 整个快照拒绝，保留旧活动版本 |
| 同名字段来自不同数据集 | 内部限定 ID 不冲突 |
| 重复通知、旧任务晚完成、执行中更换定义 | 幂等、旧任务不覆盖新版、查询固定快照 |

## 12. 调研来源

- [Ossie 主分支规范及版本说明](https://github.com/apache/ossie/blob/main/core-spec/spec.md)
- [固定提交的 0.1.1 规范](https://github.com/apache/ossie/blob/faf581054dcf7964d5fe0ceae7d6f415c8ce32a5/core-spec/spec.md)
- [固定提交的 0.1.1 JSON Schema](https://github.com/apache/ossie/blob/faf581054dcf7964d5fe0ceae7d6f415c8ce32a5/core-spec/osi-schema.json)
- [GitHub Releases 页面](https://github.com/apache/ossie/releases)
- [dbt 的 Ossie 导入限制](https://docs.getdbt.com/docs/build/ossie-semantic-models)
- 本仓库：`backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/semantic/`。
- 本仓库：`backend/agent-data-source/src/main/java/com/datagent/agentv2/datasource/query/StarRocksSqlPolicyValidator.java`。
- 本仓库：`backend/agent-workflow/src/main/java/com/datagent/agentv2/workflow/runtime/QueryPlanValidator.java`。
