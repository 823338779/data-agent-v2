# Data Agent 受控数据分析 Workflow 设计

## 1. 背景与结论

当前系统以 `ReactExecutionEngine` 驱动模型与 Tool 的循环，并通过 Skill 渐进披露部分 Tool。该机制适合通用工具型任务，但不适合用提示词或 Skill 约定“查询语义资源、生成查询计划、生成 SQL、查询数据、分析结果”的固定数据分析流程。

本设计采用以下结论：

> 应用代码负责路由、状态转换、校验、权限、重试和副作用；模型只在路由、查询计划、SQL 生成和结果分析等需要语义判断的节点工作。

数据分析 Workflow 不注册为高阶 Tool，也不由外层 ReAct 决定是否、何时或重复调用。系统在 execution 顶层先选择执行策略，数据 Workflow 与现有 ReAct 是同级策略：

```text
LocalAgentExecutionLauncher
  -> RoutedAgentExecutionEngine
       -> ExecutionRouteResolver
            -> DATA_ANALYSIS_WORKFLOW -> DataAnalysisWorkflowEngine
            -> GENERAL_REACT          -> ReactExecutionEngine
```

本阶段明确不实现 Workflow 步骤持久化、断点恢复、跨进程接管、人工审批、多数据源和并行查询。Workflow 步骤、完整 SQL 和查询结果只存在于当前 JVM 的 execution 生命周期内。

## 2. 目标

1. 用强类型有限状态机实现可预测、可测试的数据分析主流程。
2. 以结构化 `QueryPlan` 代替 MQL，避免引入额外查询语言和编译器。
3. 支持单个 StarRocks 数据源，以及可配置数量的顺序查询任务。
4. 通过数据库元数据与 YAML 业务语义构建可检索的语义资源目录。
5. 只执行经过本地安全策略和 StarRocks `EXPLAIN` 双重校验的只读 SQL。
6. 通过现有会话 SSE 实时展示 Workflow 阶段和已验证的完整 SQL。
7. 复用现有 execution 的取消、截止时间、最终回答持久化和失败收敛能力。
8. 保留现有 ReAct、Tool 和 Skill 能力，供非数据分析任务继续使用。

## 3. 非目标

- 不把数据分析流程写入 Skill。
- 不新增 `data_analysis_workflow` 高阶 Tool。
- 不允许模型动态定义状态、跳转边或重试策略。
- 不持久化 Workflow Run、Step、Event、SQL 或查询结果。
- 不支持应用重启后的 Workflow 恢复。
- 不支持多个数据源、跨数据源 JOIN 或运行期数据源选择。
- 不支持 Workflow 内并行查询。
- 不支持模型在结果分析阶段无限追加查询。
- 不引入通用 BPM、Batch 或持久化 Workflow 框架。
- 不在第一版引入向量数据库或 embedding 检索。
- 不修改 Skill 激活语义，也不让数据 Workflow 依赖 Skill。

## 4. 总体架构

### 4.1 顶层执行路由

`LocalAgentExecutionLauncher` 不再直接调用 `ReactExecutionEngine`，而是调用 `RoutedAgentExecutionEngine`。后者负责：

1. 使用现有 `LocalExecutionGuard` 检查 execution 状态、取消和截止时间；
2. 准备有界的当前用户问题与会话上下文；
3. 调用 `ExecutionRouteResolver`；
4. 根据结构化路由结果选择唯一执行策略；
5. 统一清理运行期 Workflow 视图；
6. 将未处理异常交给现有 execution 终止边界收敛。

首版只注册两个路由：

```java
public enum ExecutionRoute {
    DATA_ANALYSIS_WORKFLOW,
    GENERAL_REACT
}
```

`ExecutionRouteResolver` 的模型输出必须经过 JSON Schema 与枚举校验。模型不能返回类名、Bean 名、Tool 名、Prompt 或自定义流程。路由模型失败、输出非法或超时时，默认进入 `GENERAL_REACT`，不得在路由失败时直接访问数据库。

后续若产品入口能够明确指定数据分析模式，可由服务端 Agent 配置直接选择 `DATA_ANALYSIS_WORKFLOW`，跳过模型路由；本阶段不扩展 REST 请求协议。

### 4.2 同级执行策略

```text
                         +--------------------------+
                         | RoutedAgentExecutionEngine|
                         +------------+-------------+
                                      |
                         +------------+-------------+
                         |                          |
              DATA_ANALYSIS_WORKFLOW         GENERAL_REACT
                         |                          |
             DataAnalysisWorkflowEngine     ReactExecutionEngine
                         |                          |
              受控业务端口与模型节点          Agent Tool / Skill
```

`GENERAL_REACT` 完整保留现有 ReAct 行为、Tool 可见性、Skill 激活和调用限制。

`DATA_ANALYSIS_WORKFLOW` 不访问 `ToolSupplyRegistry`，其内部数据能力是应用服务端口，不是模型可自由选择的 Tool。Workflow 与 ReAct 不能在同一次 execution 中互相嵌套或来回切换。

### 4.3 Maven Module

新增：

```text
agent-data-source
  StarRocks 连接、元数据、语义 YAML、目录检索、只读 SQL 执行

agent-workflow
  Route、QueryPlan、状态机、Policy、Verifier、运行期事件及模型端口
```

依赖方向：

```text
agent-domain
     ^
     |
agent-data-source <- agent-workflow
                         ^
                         |
                     agent-web
                    /    |    \
          agent-tool  agent-skill  existing context modules
                         ^
                         |
                     agent-server
```

具体边界：

- `agent-data-source` 不感知会话、execution、模型、SSE、Tool 或 Skill。
- `agent-workflow` 可依赖 `agent-data-source` 暴露的语义目录与查询契约，但不依赖 `agent-web`、`agent-tool`、Mapper 或 MyBatis API。
- `agent-workflow` 定义 `WorkflowModelPort`、`WorkflowEventPort` 和 `WorkflowFinalAnswerPort`，由 `agent-web` 提供适配器。
- `agent-web` 负责路由输入的会话上下文装配、运行策略选择、SSE 适配和最终回答输出。
- `agent-server` 继续只负责启动、配置和运行时装配。
- `agent-persistence` 不新增 Workflow 表；现有会话、execution、消息、事件和流 checkpoint 保持权威。
- `agent-evals` 只承载回归评测，不成为运行时依赖。

## 5. Data Analysis Workflow 状态机

### 5.1 主路径

```text
DISCOVER_SEMANTIC_RESOURCES
  -> BUILD_QUERY_PLAN
  -> VALIDATE_QUERY_PLAN
  -> for each QueryTask in order
       GENERATE_SQL
       -> VALIDATE_SQL
       -> EXPLAIN_SQL
       -> EXECUTE_QUERY
  -> VERIFY_RESULTS
  -> ANALYZE_RESULTS
  -> FINALIZE_RESPONSE
  -> SUCCEEDED
```

execution 顶层的 `ROUTING` 发生在进入该状态机之前。路由选中数据分析策略后，创建 Workflow Run 并发布 `workflow_started`。

异常终态：

```text
FAILED
CANCELLED
TIMED_OUT
```

状态只能由应用代码迁移。模型输出只作为节点数据，不能携带或覆盖 next step。

### 5.2 运行期上下文

```java
public record WorkflowRunContext(
        String workflowId,
        String tenantId,
        String conversationSessionId,
        String executionId,
        String question,
        Instant hardDeadlineAt,
        WorkflowStep currentStep,
        SemanticResourceSet semanticResources,
        QueryPlan queryPlan,
        List<QueryTaskRun> taskRuns,
        AnalysisResult analysisResult) {}
```

实际实现应采用受控修改或不可变替换，禁止多个节点并发修改同一上下文。上下文仅存在于当前 execution 的虚拟线程及 `WorkflowRunRegistry` 的有界快照中。

### 5.3 允许的回退

```text
VALIDATE_QUERY_PLAN -- invalid --> BUILD_QUERY_PLAN
VALIDATE_SQL        -- invalid --> GENERATE_SQL
EXPLAIN_SQL         -- syntax/schema error --> GENERATE_SQL
EXECUTE_QUERY       -- syntax/schema drift --> GENERATE_SQL
```

规则：

- QueryPlan 默认最多生成 3 次；
- 每个 QueryTask 的 SQL 默认最多生成 3 次；
- 瞬时连接故障可使用完全相同的 SQL 重试 1 次；
- SQL 超时、结果过大和策略拒绝不能通过扩大范围后绕过；
- 空结果是成功查询，进入结果验证与分析，不自动删除过滤条件；
- `ANALYZE_RESULTS` 不得新增 QueryTask；
- 所有尝试共享 Workflow 总截止时间；
- 用户取消或 execution 截止后，不得继续模型、数据库或 SSE 副作用。

## 6. QueryPlan

首版不引入 MQL。模型输出结构化 `QueryPlan`：

```java
public record QueryPlan(String analysisGoal, List<QueryTask> tasks) {}

public record QueryTask(
        String taskId,
        String purpose,
        List<String> semanticResourceIds,
        List<String> metrics,
        List<String> dimensions,
        List<QueryFilter> filters,
        QueryTimeRange timeRange,
        List<QueryOrder> orderBy,
        Integer limit) {}
```

QueryPlan 校验必须保证：

- QueryTask 数量为正且不超过服务端配置；
- `taskId` 非空且唯一；
- 指标、维度、过滤字段和排序字段来自命中的语义资源；
- JOIN 路径只使用 YAML 声明的关系；
- 时间范围、操作符、值类型和 limit 合法；
- 每个任务都有明确用途；
- QueryPlan 不包含 JDBC 地址、凭据、物理连接参数或任意 SQL 片段。

任务按列表顺序执行。首版不根据依赖关系并行，也不允许模型输出任意 DAG。

`max-query-tasks` 是服务端配置，默认 `5`，允许配置范围为 `1..20`。启动时超出范围直接失败。

## 7. 单 StarRocks 数据源

### 7.1 配置

```yaml
data-agent:
  datasource:
    jdbc-url: ${DATA_AGENT_STARROCKS_JDBC_URL}
    username: ${DATA_AGENT_STARROCKS_USERNAME}
    password: ${DATA_AGENT_STARROCKS_PASSWORD}
    default-catalog: default_catalog
    default-database: analytics
    connect-timeout: 5s
    query-timeout: 30s
    max-rows: 1000
    max-result-bytes: 4MB
    semantic-config: classpath:semantic/starrocks.yaml
```

只支持一个数据源，不引入 `dataSourceId`、数据源注册表或运行期选择。密码必须来自环境变量或外部密钥注入，不得提交到仓库。

应用启动时必须：

1. 校验配置完整性；
2. 建立 StarRocks 连接；
3. 读取 `information_schema`；
4. 解析语义 YAML；
5. 校验所有表、字段、类型、指标和关系引用；
6. 构建不可变语义目录。

配置缺失、数据库不可连接或语义引用不一致时应用启动失败。第一版不做运行期热更新；结构或 YAML 变化后通过重启重新加载。

数据库账号必须只授予查询所需的最小只读权限。JDBC `readOnly` 只作为附加信号，不能替代数据库授权。

### 7.2 语义 YAML

```yaml
version: 1

datasets:
  - id: orders
    table: fact_orders
    name: 订单
    description: 已创建的业务订单
    synonyms: [交易, 销售订单]
    default-time-field: paid_at
    fields:
      - id: order_amount
        column: paid_amount
        name: 实付金额
        role: measure
        data-type: decimal
        unit: CNY
        synonyms: [销售额, 成交额]
      - id: region
        column: region_code
        name: 地区
        role: dimension

metrics:
  - id: paid_sales
    name: 销售额
    dataset: orders
    description: 已支付订单的实付金额合计
    aggregation: sum
    field: order_amount
    filters:
      - field: order_status
        operator: eq
        value: PAID
    unit: CNY

relationships:
  - id: order_product
    left-dataset: orders
    left-field: product_id
    right-dataset: products
    right-field: product_id
    join-type: left
    cardinality: many-to-one
```

YAML 是受信任的服务端配置，但仍需严格 Schema 校验。关系必须使用结构化左右字段，不接受任意 JOIN SQL。指标优先使用结构化聚合、字段和固定过滤表达；若后续必须支持复杂受信任表达式，应单独扩展 Schema 和安全校验，不在首版接受任意 `sqlExpression`。

### 7.3 语义检索

第一版使用本地确定性召回：

- 检索名称、描述、同义词、表注释和字段注释；
- 精确名称与同义词命中优先；
- 根据指标所属数据集和已声明关系补齐必要资源；
- 只返回有界候选，不把整个数据库 Schema 放入模型上下文；
- 返回资源 ID、业务含义、物理映射、指标口径和允许的关联路径。

语义候选数量由 `max-semantic-resources` 控制，默认 `30`。

## 8. Workflow 模型端口

```java
public interface WorkflowModelPort {
    ExecutionRoute route(RoutePrompt prompt);

    QueryPlan buildQueryPlan(QueryPlanPrompt prompt);

    GeneratedSql generateSql(SqlGenerationPrompt prompt);

    AnalysisResult analyzeResults(ResultAnalysisPrompt prompt);
}
```

内部模型调用不得获得 Tool 定义。每个输出均采用独立 JSON Schema，并在进入下一节点前执行领域校验。

- 路由输入：当前问题、有界会话上下文、固定路由摘要；
- QueryPlan 输入：问题与命中的业务语义资源；
- SQL 输入：单个 QueryTask、相关物理映射、指标口径、关系和方言规则；
- 分析输入：所有成功任务的有界结果、截断信息与分析目标。

QueryPlan、SQL 和分析调用不创建持久化 thinking 节点，也不推进现有回答流 checkpoint。它们的次数、耗时和失败只进入低敏日志、指标与 Workflow SSE 摘要。

## 9. SQL 安全与执行

### 9.1 SQL 输出

```java
public record GeneratedSql(String taskId, String sql) {}
```

模型输出大小不得超过 `max-generated-sql-bytes`。超限时整个输出失败，不能截断后执行。

### 9.2 四层校验

第一层：词法边界。

- 只允许单条语句；
- 去除首尾空白后必须是 `SELECT` 或 `WITH`；
- 拒绝 SQL 注释和模型生成的 Hint；
- 拒绝多语句、`SET`、`USE`、`SHOW`、`KILL`、DDL、DML、导入导出和事务语句。

第二层：AST 策略。

- 使用 MySQL 兼容 SQL AST 解析器处理首版允许的保守子集，不使用正则表达式作为唯一安全边界；
- 根节点必须是只读查询；
- 所有表、列和 JOIN 必须来自当前 QueryTask 授权资源；
- JOIN 条件必须匹配 YAML 声明关系；
- 拒绝任意表函数、文件函数、外部位置和未批准 Catalog；
- 拒绝模型生成的 `SET_VAR` 等资源控制 Hint；
- 顶层结果行数必须受 `max-rows` 约束，缺失或过大时由服务端 AST 安全重写。

为保证安全边界可预测，首版可以拒绝 StarRocks 支持但本地解析器无法可靠分析的复杂语法，模型应在允许的 SQL 子集中重新生成。

第三层：StarRocks 权威预检。

```sql
EXPLAIN <validated-sql>
```

只使用不会执行查询的普通 `EXPLAIN`，不使用 `EXPLAIN ANALYZE`。预检负责确认 StarRocks 方言、字段、类型和计划可生成。只有本地策略与 `EXPLAIN` 都成功的 SQL 才可通过 SSE 展示并进入执行。

第四层：受控执行。

- 使用 StarRocks 官方 JDBC Driver；
- 服务端设置连接级 `query_timeout`，不允许模型覆盖；
- 同时使用 JDBC `Statement.setQueryTimeout`；
- 取消或超时时终止当前 Statement；
- 流式读取 ResultSet；
- 读取到 `max-rows + 1` 行时丢弃额外行并将结果标记为 `truncated=true`；
- 达到最大结果字节数时丢弃当前部分结果并以 `query_result_too_large` 失败，禁止把不完整字节结果交给分析模型；
- 达到截止时间时立即停止并以 `workflow_deadline_exceeded` 失败；
- SQL、JDBC URL、凭据和数据库异常正文不进入日志或错误响应。

### 9.3 查询结果

```java
public record TabularQueryResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        boolean truncated,
        long returnedRowCount,
        long elapsedMillis) {}
```

decimal、日期、时间、布尔和 null 必须使用稳定 JSON 映射。结果只保存在 `WorkflowRunContext`，不写数据库。

分析模型输入受 `max-analysis-input-bytes` 限制。超过限制时按完整行边界截断，并强制在分析 `caveats` 中披露不完整性；如果列定义和首行本身已经超过限制，无法形成合法输入，则以 `analysis_input_too_large` 失败。

## 10. 结果验证、分析与最终回答

`VERIFY_RESULTS` 是确定性节点，至少检查：

- 每个 QueryTask 都有终态查询结果；
- 结果列与当前任务目标一致；
- 数值、时间和 null 能被序列化；
- 截断、空结果和部分输入有明确标志；
- 不存在未收敛的查询任务。

`ANALYZE_RESULTS` 输出：

```java
public record AnalysisResult(
        String summary,
        List<AnalysisFinding> findings,
        List<String> caveats) {}

public record AnalysisFinding(
        String statement,
        List<EvidenceRef> evidence) {}

public record EvidenceRef(String taskId, List<Integer> rowIndexes) {}
```

每条结论必须引用 QueryTask 和结果行。模型不能在此节点请求或追加查询。

`FINALIZE_RESPONSE` 是唯一复用现有持久化回答流的 Workflow 节点。实施时从当前 `ModelInvocationCoordinator` 中抽取或暴露不带 ToolCall 能力的最终回答协调边界，完成：

1. 建立持久化的最终模型调用边界；
2. 使用问题、分析结果、证据和 caveat 生成用户回答；
3. 通过现有 `assistant_delta` 实时输出；
4. 更新流 checkpoint；
5. 完成 assistant 消息与 execution。

Workflow 不再返回结果给外层 ReAct，也不会发生第二次 Agent 决策。

## 11. Workflow SSE

### 11.1 事件类型

在现有详细会话 SSE 中新增：

```text
workflow_snapshot
workflow_update
```

Workflow 更新的 `eventType`：

```text
workflow_started
step_started
step_progress
step_succeeded
step_failed
step_retrying
workflow_succeeded
workflow_failed
```

### 11.2 更新载荷

```json
{
  "type": "workflow_update",
  "conversationSessionId": "session-1",
  "executionId": "execution-1",
  "workflowId": "workflow-1",
  "sequence": 8,
  "eventType": "step_succeeded",
  "step": "explain_sql",
  "status": "succeeded",
  "task": {
    "taskId": "regional-sales",
    "purpose": "统计各地区最近30天销售额",
    "index": 1,
    "total": 2
  },
  "detail": {
    "semanticResourceNames": ["订单", "销售额", "地区"],
    "sql": "SELECT region_code, SUM(paid_amount) ... LIMIT 1000",
    "returnedRows": null,
    "truncated": null,
    "attempt": 1,
    "maxAttempts": 3,
    "elapsedMillis": 42,
    "message": "SQL 已通过安全校验和 StarRocks 预检"
  },
  "occurredAt": "2026-08-30T12:00:00Z"
}
```

完整 SQL 只在本地策略和 StarRocks `EXPLAIN` 均成功后发送。被拒绝的 SQL 草案不发送。发送的 SQL 不截断；超过服务端 SQL 大小上限时生成失败。

SSE 不发送：

- 查询结果数据行；
- JDBC URL、账号或密码；
- 原始数据库异常或堆栈；
- 未通过安全校验的 SQL；
- 内部模型 Prompt 或原始模型输出。

### 11.3 内存快照与重连

```java
public record WorkflowSnapshot(
        String workflowId,
        String executionId,
        long latestSequence,
        WorkflowStatus status,
        WorkflowStep currentStep,
        List<WorkflowTaskSnapshot> tasks,
        Instant startedAt,
        Instant updatedAt) {}
```

`WorkflowRunRegistry` 按 execution 保存当前完整快照和有界握手缓冲。流程为：

```text
route selected -> open workflow run
step changed  -> atomically update snapshot -> publish event
SSE reconnect -> subscribe first -> read snapshot -> merge handshake events
workflow end  -> publish terminal event -> finish final output -> cleanup
```

现有 SSE 每次连接先发送权威 `session_snapshot`，不使用 `Last-Event-ID`。若当前 execution 存在运行中的 Workflow，则随后发送 `workflow_snapshot`，再发送实时更新。

慢客户端继续使用现有有界单线程发送队列。队列溢出时关闭连接。Workflow 仍运行时，重连可从内存快照恢复当前展示；Workflow 终态清理后，刷新页面只能看到持久化的最终回答和 execution 状态。

`ModelInvocationCoordinator.completeFinalAnswer(...)` 当前会在同一事务内完成 assistant 消息、execution 和持久事件。成功事务提交后，现有 `message_update` 或 `execution_update` 不能在 Workflow registry 仍为 active 时立即关闭 SSE：

1. `ConversationEventStreamFacade` 在准备关闭终态连接前检查当前 execution 是否仍有 active Workflow；
2. 最终回答事务提交并返回后，Workflow 引擎发布 `workflow_succeeded`；
3. `WorkflowRunRegistry.finish(...)` 先把终态事件加入连接发送队列，再清理快照并通知 facade `completeAfterPending()`；
4. 失败路径在把异常交给 execution 终止服务前先发布 `workflow_failed`，因此不需要等待持久失败事件补发 Workflow 终态。

该顺序保证最终回答事务失败时不会提前发送虚假的 `workflow_succeeded`，也保证成功时终态 Workflow 事件不会被 execution 终态抢先关闭连接而丢失。

## 12. 配置与预算

```yaml
data-agent:
  workflow:
    max-query-tasks: 5
    max-query-plan-attempts: 3
    max-sql-attempts-per-task: 3
    max-transient-query-retries: 1
    max-generated-sql-bytes: 64KB
    max-semantic-resources: 30
    max-analysis-input-bytes: 512KB
    hard-timeout: 120s
```

约束：

- `max-query-tasks` 范围为 `1..20`；
- 所有次数与大小必须为正数，并设置服务端硬上限；
- Workflow deadline 取配置值与 execution 剩余截止时间的较小者；
- 单查询 timeout 必须小于 Workflow 剩余时间；
- 查询任务顺序执行；
- 重试不延长总截止时间；
- 模型、SQL、查询结果和 SSE 均使用独立大小上限。

## 13. 错误分类与收敛

稳定错误码：

```text
semantic_resource_not_found
query_plan_invalid
query_plan_attempts_exhausted
sql_generation_invalid
sql_policy_denied
sql_explain_failed
sql_attempts_exhausted
query_timeout
query_result_too_large
query_engine_unavailable
analysis_input_too_large
analysis_failed
workflow_cancelled
workflow_deadline_exceeded
workflow_internal_error
```

规则：

- SQL 语法、字段或计划错误可以进入受控 SQL 重新生成；
- 数据源不可用、超时、结果过大不通过模型自动扩大范围；
- 相同错误达到上限后立即失败；
- 用户取消优先于重试；
- 任何异常出口必须发布唯一 Workflow 终态；
- 对外只返回稳定错误码和脱敏摘要；
- 由现有 `AgentExecutionProcessTerminationService` 收敛仍为 running 的 execution；
- finally 必须清理 Workflow registry、模型临时缓冲和 JDBC 资源。

## 14. 可观测性

结构化日志和 Trace 仅记录：

- workflowId、executionId、workflow version；
- route、step、taskId、attempt；
- 模型身份与 Prompt 版本摘要；
- 耗时、token 使用量、返回行数、截断标志；
- 稳定错误码和重试次数。

不得记录 SQL、结果行、Prompt 正文、模型原始输出、JDBC URL、凭据或数据库异常正文。

Micrometer 指标使用固定低基数标签：

```text
data_agent_workflow_total{route,outcome}
data_agent_workflow_step_duration{step,outcome}
data_agent_workflow_retry_total{step,reason}
data_agent_query_duration{outcome}
data_agent_query_rows{truncated}
data_agent_sql_policy_denied_total{reason}
```

tenant、session、execution、workflow、task 和 SQL 不得作为指标标签。

## 15. 安全边界

1. 路由失败不能获得数据库访问权。
2. Workflow 数据能力不注册为 Tool，模型无法改变调用顺序。
3. 数据库账号使用最小只读权限。
4. SQL 必须同时通过本地策略和 StarRocks 预检。
5. 模型生成的 Hint、注释、外部位置、表函数和未授权资源一律拒绝。
6. SQL 只发送给当前租户、当前会话的详细 SSE 连接。
7. 完整 SQL 是瞬时敏感数据，不写数据库、不写日志。
8. 查询结果只进入有界 Workflow 内存和分析模型输入，不进入 SSE。
9. 取消或截止后不能继续发起模型调用、查询或 Workflow 更新。
10. Workflow 清理不能影响现有持久化会话和 execution 投影。

当前项目尚未重新设计认证和租户隔离，运行时继续使用固定 `TenantContext` 与 `OperatorContext`。本设计不声称该固定身份具备生产级授权能力；正式部署前仍需完成真实认证、数据源权限和会话访问控制。

## 16. 测试策略

### 16.1 `agent-data-source`

- 正确解析 YAML 并合并数据库元数据；
- 表、字段、指标、关系或类型引用错误时启动失败；
- JDBC 配置缺失或首次连接失败时启动失败；
- 语义检索排序稳定；
- 行数、字节数和超时限制生效；
- ResultSet 类型映射稳定；
- 凭据和连接信息不进入错误对象。

### 16.2 SQL 安全

- 普通 `SELECT` 与 `WITH ... SELECT` 通过；
- 多语句、注释、Hint、DDL、DML、`SET`、`SHOW` 和导入导出被拒绝；
- 未授权表、字段和 JOIN 被拒绝；
- 表函数、文件函数和外部位置被拒绝；
- 缺失或过大 LIMIT 被安全收紧；
- 解析器不支持的复杂语法安全失败；
- 只有本地校验与 `EXPLAIN` 均成功的 SQL 才能执行和发送 SSE；
- 超长 SQL 失败而不是截断执行。

### 16.3 `agent-workflow`

使用 Fake Model、Fake Semantic Catalog、Fake Query Gateway 和 Fake Event Port 验证：

- 路由只返回注册枚举，失败时回退 `GENERAL_REACT`；
- 正常单任务与最大配置数量多任务流程；
- QueryPlan 超量或引用越界被拒绝；
- QueryPlan 与 SQL 只按允许边回退；
- SQL 字段错误携带脱敏反馈后重新生成；
- 空结果仍进入分析；
- 分析节点不能追加查询；
- 超时、取消和截止时间停止当前节点；
- 任意异常只产生一个 Workflow 终态；
- registry 在 Workflow 结束后清理。

### 16.4 SSE

- `session_snapshot` 后发送当前 `workflow_snapshot`；
- Workflow event sequence 单调；
- 握手期间事件不丢失、不乱序；
- 校验与 `EXPLAIN` 前不发送 SQL；
- 预检成功后发送完整 SQL；
- SSE 不包含结果行、凭据或原始异常；
- 慢客户端关闭后，运行中重连可恢复当前快照；
- Workflow 终态清理后重连不再包含内部步骤；
- 现有 assistant delta、message update、execution update 不回归。

### 16.5 服务级契约

默认自动化测试不得连接真实外部系统：

```text
用户消息
  -> route DATA_ANALYSIS_WORKFLOW
  -> workflow step SSE
  -> fake StarRocks 返回数据
  -> structured analysis
  -> FINALIZE_RESPONSE assistant delta
  -> assistant message persisted
  -> execution succeeded
```

提供显式启用的 StarRocks 集成测试 Profile。只有调用方提供测试连接配置时才运行，测试不得打印连接信息或把凭据写入报告。

## 17. 验收标准

1. 数据分析主流程不依赖 Skill 或 ReAct 提示词约定。
2. 顶层应用代码在数据 Workflow 与通用 ReAct 之间选择唯一策略。
3. 数据 Workflow 不注册为 Tool，也不嵌套外层 ReAct。
4. 状态机严格执行预定义边、次数、预算、取消和 deadline。
5. `QueryPlan` 支持服务端可配置的顺序查询任务数量。
6. 单 StarRocks 数据源在启动时完成连接与语义目录 fail-fast 校验。
7. 只有通过本地策略和 StarRocks `EXPLAIN` 的 SQL 才能执行并通过 SSE 展示。
8. Workflow 步骤、完整 SQL 和查询结果不写数据库。
9. 运行期 SSE 实时展示步骤，重连可恢复内存快照。
10. Workflow 清理后刷新页面只保留最终回答和 execution 状态。
11. 最终回答由 Workflow 的 `FINALIZE_RESPONSE` 直接完成，不返回外层 ReAct 二次决策。
12. 非数据分析请求继续使用现有 ReAct、Tool 和 Skill，行为不回归。
13. 默认 Maven 测试不依赖真实 StarRocks。

## 18. 实施切片

为避免同时改动路由、数据源、安全、SSE 和最终回答，实施计划应拆成可独立验证的纵向切片：

1. 建立 `agent-data-source`、配置、元数据和 YAML 语义目录；
2. 建立 QueryPlan、状态机核心、限制与 Fake 端口；
3. 实现 SQL 安全策略、StarRocks `EXPLAIN` 和有界查询；
4. 增加顶层路由与同级执行策略，保留 ReAct 回归；
5. 接入 Workflow 运行期 SSE 快照与更新；
6. 接入分析与 `FINALIZE_RESPONSE`，完成服务级闭环；
7. 增加可选 StarRocks 集成 Profile、Eval 与运维说明。

每个切片都必须先以失败测试描述行为，再实现最小代码并运行与修改范围匹配的 Maven 验证。
