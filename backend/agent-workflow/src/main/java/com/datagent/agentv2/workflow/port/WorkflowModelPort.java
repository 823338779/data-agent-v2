package com.datagent.agentv2.workflow.port;

import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.GeneratedSql;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryPlanPrompt;
import com.datagent.agentv2.workflow.model.ResultAnalysisPrompt;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import com.datagent.agentv2.workflow.model.SqlGenerationPrompt;

/** Workflow 对模型的四个窄端口；模型不能决定状态迁移。 */
public interface WorkflowModelPort {
    ExecutionRoute route(RoutePrompt prompt);

    QueryPlan buildQueryPlan(QueryPlanPrompt prompt);

    GeneratedSql generateSql(SqlGenerationPrompt prompt);

    AnalysisResult analyzeResults(ResultAnalysisPrompt prompt);
}
