package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.GeneratedSql;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryPlanPrompt;
import com.datagent.agentv2.workflow.model.ResultAnalysisPrompt;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import com.datagent.agentv2.workflow.model.SqlGenerationPrompt;
import com.datagent.agentv2.workflow.port.WorkflowModelPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 使用现有模型网关实现四个无 Tool 的 Workflow 智能节点。 */
@Service
@RequiredArgsConstructor
public final class AgentWorkflowModelAdapter implements WorkflowModelPort {

    private static final String ROUTE_SYSTEM =
            """
            你是执行策略路由器。只有需要查询业务数据、计算指标、分组、趋势、对比或基于数据库事实分析的问题，
            才选择 DATA_ANALYSIS_WORKFLOW；其他问题选择 GENERAL_REACT。
            只返回 JSON：{"route":"DATA_ANALYSIS_WORKFLOW"} 或 {"route":"GENERAL_REACT"}。
            """;

    private static final String PLAN_SYSTEM =
            """
            你是数据分析查询计划器。根据问题和服务端提供的语义资源生成顺序 QueryPlan，不得输出 SQL、连接信息或任意 DAG。
            只返回纯 JSON，字段必须严格为 analysisGoal 和 tasks。每个 task 包含 taskId、purpose、semanticResourceIds、
            metrics、dimensions、filters、timeRange、orderBy、limit。filters 使用 field/operator/values，
            orderBy 使用 field/direction，timeRange 为 null 或 field/startInclusive/endExclusive（ISO-8601 UTC）。
            """;

    private static final String SQL_SYSTEM =
            """
            你是 StarRocks SQL 生成器。只可使用输入中授权的物理表、字段、指标和关系，生成一条只读 SELECT 或 WITH SELECT。
            不得使用注释、Hint、分号、表函数、系统表或写操作。只返回纯 JSON：{"taskId":"...","sql":"..."}。
            """;

    private static final String ANALYSIS_SYSTEM =
            """
            你是数据分析器。只能依据给定 QueryTask 结果生成结论，不得要求新增查询。
            每条 finding 必须以 evidence 引用 taskId 和从 0 开始的 rowIndexes。
            只返回纯 JSON：{"summary":"...","findings":[{"statement":"...","evidence":[{"taskId":"...","rowIndexes":[0]}]}],"caveats":[]}。
            空结果时允许 findings 为空，但 summary 和 caveats 必须准确说明数据不足。
            """;

    private final AgentModelGateway model;
    private final WorkflowJsonDecoder json;

    @Override
    public ExecutionRoute route(RoutePrompt prompt) {
        return json.decodeRoute(call(ROUTE_SYSTEM, json.encode(prompt)));
    }

    @Override
    public QueryPlan buildQueryPlan(QueryPlanPrompt prompt) {
        return json.decodeQueryPlan(call(PLAN_SYSTEM, json.encode(prompt)));
    }

    @Override
    public GeneratedSql generateSql(SqlGenerationPrompt prompt) {
        return json.decodeSql(call(SQL_SYSTEM, json.encode(prompt)));
    }

    @Override
    public AnalysisResult analyzeResults(ResultAnalysisPrompt prompt) {
        return json.decodeAnalysis(call(ANALYSIS_SYSTEM, json.encode(prompt)));
    }

    private String call(String systemPrompt, String input) {
        ModelInvocationResult result =
                model.complete(
                        List.of(
                                ModelInputMessage.plain("system", systemPrompt),
                                ModelInputMessage.plain("user", input)),
                        List.of(),
                        ignored -> {});
        if (!(result instanceof ModelInvocationResult.FinalAnswer answer)) {
            throw new IllegalArgumentException("Workflow 模型节点不允许返回 ToolCalls");
        }
        return answer.answer();
    }
}
