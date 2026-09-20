# MetricFlow 本地演示：从指标定义到实际查询

本示例已在 Windows、Python 3.14 上实际运行，使用 MetricFlow 0.213.0、dbt-metricflow 0.15.0、dbt Core 1.12.5、dbt-duckdb 1.11.0、DuckDB 1.5.5。

MetricFlow 是指标查询引擎。本示例通过 `mf` 命令调用它，查询结束后进程退出；没有需要常驻启动的 Web 服务，也没有浏览器地址。DuckDB 数据保存在 `.local/metricflow_demo.duckdb`。虚拟环境、数据库、运行日志和生成结果均被 Git 忽略。

建议按下面顺序逐条执行，每一步先看输出，再继续。2026-09-20 已将 `target/`、`.local/` 和 `logs/` 移出示例目录，备份到仓库的 `tmp/metricflow-reset-20260920/`，保留虚拟环境及所有示例源文件。下文结果是重置前的实测参考；现在需要先完成第 1 节的分步构建，才能查询。

## 1. 准备终端

在 PowerShell 执行：

```powershell
cd E:\IdeaProjects\agent-root\data-agent-v2\examples\metricflow
$env:DBT_PROFILES_DIR = (Get-Location).Path
$env:PYTHONUTF8 = '1'
$env:DBT_SEND_ANONYMOUS_USAGE_STATS = 'false'
.\.venv\Scripts\mf.exe --version
```

`DBT_PROFILES_DIR` 让 MetricFlow 使用当前示例的配置；`PYTHONUTF8` 避免 Windows 输出中文和符号时的编码问题。每次新开终端都执行这段。

**仅在虚拟环境不存在时安装：**

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

### 1.1 只解析定义

```powershell
.\.venv\Scripts\dbt.exe parse --profiles-dir .
```

观察：`target/` 出现 `manifest.json`、`semantic_manifest.json` 等清单。这个步骤解析项目和语义定义，不执行建表或指标查询，不会生成模型的 `compiled/`、`run/` SQL。此时先打开 `target/semantic_manifest.json` 看指标与实体定义。

### 1.2 导入原始 CSV

```powershell
New-Item -ItemType Directory -Path .local -Force | Out-Null
.\.venv\Scripts\dbt.exe seed --profiles-dir .
```

观察：`.local/metricflow_demo.duckdb` 出现，数据库中建立 `demo.raw_orders` 和 `demo.raw_customers` 两张原始表，分别导入 12 行和 4 行。订单事实表和客户维度表尚未建立。

### 1.3 只编译模型 SQL

```powershell
.\.venv\Scripts\dbt.exe compile --profiles-dir .
```

观察：打开 `target/compiled/metricflow_demo/models/fct_orders.sql`，`ref('raw_orders')` 已替换成真实表名。编译不负责把这些模型物化成表。

### 1.4 执行模型建表

```powershell
.\.venv\Scripts\dbt.exe run --profiles-dir .
```

观察：数据库中建立 `fct_orders`、`dim_customers`、`time_spine` 三张模型表。打开 `target/run/metricflow_demo/models/fct_orders.sql`，观察 dbt 在查询外增加的 `CREATE TABLE ... AS (...)`。这些 SQL 均由 dbt 生成。

### 1.5 检查数据

```powershell
.\.venv\Scripts\dbt.exe test --profiles-dir .
```

预期 10 项数据测试通过。到这里数据和语义清单才都准备好，可以继续第 3 节的 MetricFlow 命令。

以后需要一次完成导入、建模和测试时，可以使用：

```powershell
.\.venv\Scripts\dbt.exe build --profiles-dir .
```

此命令把两个 CSV 导入 DuckDB，构建三个数据模型，执行十项数据测试，并生成语义清单。它会重建当前示例的表；本目录配置只指向自己的 DuckDB 文件。

## 2. 先理解样例与分工

| 文件 | 用途 |
| --- | --- |
| `seeds/raw_orders.csv` | 12 笔虚构订单，跨 2026 年 1 月和 2 月 |
| `seeds/raw_customers.csv` | 4 名虚构客户，所属地区为 east、north、south |
| `models/fct_orders.sql` | 保留订单明细及状态，转换日期和金额类型 |
| `models/dim_customers.sql` | 保留客户地区与客群 |
| `models/time_spine.sql` | 连续日历，用于累计与时间偏移 |
| `models/semantic.yml` | 定义实体、维度、8 个指标及其业务口径 |

数据中 10 笔订单为 `paid`，1 笔 `cancelled` 金额 500 元，1 笔 `pending` 金额 600 元。所有订单金额合计 3,370 元，但已支付收入只有 2,270 元。

dbt 负责建表；MetricFlow 读取语义定义，根据查询动态生成 SQL；DuckDB 执行 SQL。这里没有提前建立“月度收入表”或“地区收入表”。

## 3. 功能一：发现指标与可用维度

```powershell
.\.venv\Scripts\mf.exe list metrics
.\.venv\Scripts\mf.exe list dimensions --metrics revenue
.\.venv\Scripts\mf.exe list dimension-values --metrics revenue --dimension customer__region
```

应看到 8 个指标；地区取值为 `east`、`north`、`south`。

| 指标 | 口径 |
| --- | --- |
| `revenue` | 已支付订单金额合计，元 |
| `paid_orders` | 已支付订单去重数 |
| `total_cost` | 已支付订单的成本合计，元 |
| `average_order_value` | 收入 ÷ 已支付订单数，元/单 |
| `gross_profit` | 收入 − 成本，元 |
| `gross_margin` | 毛利润 ÷ 收入，小数比例 |
| `cumulative_revenue` | 从数据起点累计至当前周期末的收入，元 |
| `revenue_growth_mom` | 当前月相对上一月的收入增长，小数比例 |

`customer__region` 中的双下划线表示“客户实体下的地区维度”，也为引擎指明语义关联路径。

## 4. 功能二：统一口径、比率与派生指标

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,paid_orders,average_order_value,gross_profit,gross_margin
```

实际结果：

| revenue | paid_orders | average_order_value | gross_profit | gross_margin |
| ---: | ---: | ---: | ---: | ---: |
| 2270 | 10 | 227 | 908 | 0.4 |

这条查询没有写 `status = 'paid'`，过滤条件来自指标定义。例如：

```yaml
- name: revenue
  label: 已支付收入
  type: simple
  agg: sum
  expr: amount
  filter: "{{ Dimension('order__status') }} = 'paid'"
```

收入与订单数各自采用相同支付口径，客单价引用这两个指标。按地区或月份查询时，客单价会按该组的收入和订单数重新计算。毛利率 `0.4` 表示 40%，不是 0.4%。

## 5. 功能三：切换时间粒度

按月统计：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,paid_orders,average_order_value --group-by metric_time__month --order metric_time__month
```

| 月份 | 收入 | 已支付订单数 | 客单价 |
| --- | ---: | ---: | ---: |
| 2026-01 | 1000 | 5 | 200 |
| 2026-02 | 1270 | 5 | 254 |

换成日粒度，限定日期范围：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue --group-by metric_time__day --start-time 2026-01-05 --end-time 2026-01-06 --order metric_time__day
```

结果为 1 月 5 日 300 元、1 月 6 日 150 元。`metric_time` 映射到语义模型指定的 `ordered_at`。月度结果以当月 1 日表示月份；命令更换粒度不会修改指标定义或重建表。

## 6. 功能四：跨表关联与排序

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,paid_orders --group-by customer__region --order=-revenue
```

| 地区 | 收入 | 已支付订单数 |
| --- | ---: | ---: |
| east | 1020 | 5 |
| north | 700 | 2 |
| south | 550 | 3 |

订单表没有 `region`。引擎根据 `fct_orders.customer_id` 的 foreign entity 和 `dim_customers.customer_id` 的 primary entity 自动推导 JOIN。主键唯一性很关键，否则关联可能放大金额；本示例用 dbt 的唯一性与外键测试验证数据。

当前安装版本的排序参数是 `--order`，以实际 `mf query --help` 为准。

## 7. 功能五：按维度筛选

只看华东地区，继续按月统计：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,paid_orders --group-by metric_time__month --where "{{ Dimension('customer__region') }} = 'east'" --order metric_time__month
```

| 月份 | 华东收入 | 已支付订单数 |
| --- | ---: | ---: |
| 2026-01 | 550 | 3 |
| 2026-02 | 470 | 2 |

地区不必出现在输出分组中，也可以用于筛选。该条件会与指标自带的已支付过滤条件共同生效。

## 8. 功能六：累计与月环比

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,cumulative_revenue,revenue_growth_mom --group-by metric_time__month --start-time 2026-01-01 --end-time 2026-02-28 --order metric_time__month
```

| 月份 | 当月收入 | 累计收入 | 月环比 |
| --- | ---: | ---: | ---: |
| 2026-01 | 1000 | 1000 | 空 |
| 2026-02 | 1270 | 2270 | 0.27 |

2 月月环比为 `(1270 - 1000) / 1000 = 27%`。1 月没有上一月收入，结果为空。累计指标的 `period_agg: last` 表示按月展示时取月末累计值。

连续日历 `time_spine` 支持累计计算和时间偏移。本例日历只覆盖 2025-12-01 至 2026-02-28；扩展数据日期时也应扩展日历。月环比指标按本节的月粒度使用。

## 9. 功能七：查看生成的 SQL 和逻辑计划

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue --group-by customer__region --explain
```

`--explain` 用于编译并展示查询，不执行数据查询。实际生成 SQL 的核心片段如下，别名和排版做了简化：

```sql
select c.region, sum(o.amount) as revenue
from metricflow_demo.demo.fct_orders o
left join metricflow_demo.demo.dim_customers c
    on o.customer_id = c.customer_id
where o.status = 'paid'
group by c.region
```

观察三个关键点：自动选择客户表、自动生成 JOIN、自动加入支付过滤条件。要显示 MetricFlow 的逻辑数据流计划：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue --group-by customer__region --explain --show-dataflow-plan
```

这个逻辑计划属于 MetricFlow，不是 DuckDB 的物理执行计划。要保存完整原始输出，可在完成构建后执行：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue --group-by customer__region --explain --show-dataflow-plan --quiet | Set-Content -Encoding utf8 .local/region-explain.txt
```

## 10. 功能八：配置校验与错误反馈

```powershell
.\.venv\Scripts\mf.exe validate-configs --semantic-validation-workers 1 --dw-timeout 30
```

该命令校验语义定义，并连接本地数据库验证模型、维度、实体和指标。此次执行全部为零错误、零警告。

下面故意输入一个不存在的维度，预期命令失败：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue --group-by customer__missing_dimension
```

它会在查询解析时拒绝该维度，并提供 `customer__region`、`customer__segment` 等候选，不会把这个名称直接当作数据库字段执行。

## 11. 修改、导出与已完成的验证

只修改 `models/semantic.yml` 中的指标口径时，刷新清单后重新查询：

```powershell
.\.venv\Scripts\dbt.exe parse --profiles-dir .
```

修改 SQL 模型或 CSV 时，重新执行第 1 节的 `dbt build`。

导出查询结果：

```powershell
.\.venv\Scripts\mf.exe query --metrics revenue,paid_orders --group-by customer__region --order=-revenue --csv .local/regions.csv
```

重置前曾完成以下验证，供你重新执行时对照；本次重置后未自动重建或运行查询：

- `pip check`：依赖无冲突。
- `dbt build`：2 个 seed、3 个模型、10 项数据测试全部成功。
- `mf validate-configs`：包含数据库验证，零错误、零警告。
- 6 组实际查询结果（总量、按月、按地区、华东筛选、累计与环比、按日）逐组与原始 CSV 的独立计算核对一致。旧 CSV 已随 `.local/` 移到备份目录；需要保存新结果时在对应查询后添加 `--csv .local/文件名.csv`。
- `--explain` 生成的原始 SQL 另行交给 DuckDB 执行，地区汇总结果一致。
- 不存在的维度被拒绝，退出码为 1。

dbt 构建时会额外提示：OSI 导出文档不能完整表示累计指标的窗口和粒度语义。该提示针对 OSI 交换格式；本示例的 `mf` 查询使用 `semantic_manifest.json`，累计结果已实际验证。

参考：[MetricFlow 官方仓库](https://github.com/dbt-labs/metricflow)、[命令文档](https://docs.getdbt.com/docs/build/metricflow-commands)、[语义模型定义](https://docs.getdbt.com/docs/build/semantic-models)。本指南以已安装版本的命令帮助和实际输出为准。
