package com.datagent.agentv2.workflow.port;

import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;

/** 将结构化分析转换为现有会话最终回答的端口。 */
@FunctionalInterface
public interface WorkflowFinalAnswerPort {
    void complete(WorkflowRunRequest run, AnalysisResult analysis);
}
