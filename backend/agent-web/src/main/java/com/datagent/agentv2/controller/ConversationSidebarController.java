package com.datagent.agentv2.controller;

import com.datagent.agentv2.conversation.project.ConversationSidebarQueryService;
import com.datagent.agentv2.conversation.project.ConversationSidebarView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 侧栏轻量聚合入口；复用既有会话摘要，不装配消息正文。 */
@RestController
@RequiredArgsConstructor
public class ConversationSidebarController {
    private final ConversationSidebarQueryService sidebar;

    @GetMapping("/api/v1/agent-sidebar")
    ConversationSidebarView get() {
        return sidebar.getSidebar();
    }
}
