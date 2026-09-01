package com.datagent.agentv2.workflow.model;

import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;

/** SQL 生成节点输入；重试时只暴露稳定安全错误，不包含数据库原始异常。 */
public record SqlGenerationPrompt(
        QueryTask task, SemanticResourceSet resources, int maxRows, String previousSafeError) {}
