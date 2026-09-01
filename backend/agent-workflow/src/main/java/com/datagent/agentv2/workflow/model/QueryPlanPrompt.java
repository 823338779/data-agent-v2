package com.datagent.agentv2.workflow.model;

import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;

/** QueryPlan 节点的强类型输入。 */
public record QueryPlanPrompt(String question, SemanticResourceSet resources, int maxQueryTasks) {}
