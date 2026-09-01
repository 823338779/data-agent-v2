package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 路由节点的有界输入，不包含 Tool payload 或连接配置。 */
public record RoutePrompt(String question, List<String> recentTurns) {
    public RoutePrompt {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
