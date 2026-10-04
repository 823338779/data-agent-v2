package com.datagent.agentv2.server.context;

import com.datagent.agentv2.context.hierarchical.configuration.StepCompactionProperties;
import com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository;
import com.datagent.agentv2.conversation.context.ContextExecutionFactsFactory;
import com.datagent.agentv2.conversation.context.ContextModelBudgetProvider;
import com.datagent.agentv2.conversation.context.ContextResultReaderAdapter;
import com.datagent.agentv2.conversation.process.ContextPayloadArchive;
import com.datagent.agentv2.conversation.tool.builtin.ContextResultReadTool;
import com.datagent.agentv2.conversation.tool.context.ContextResultReader;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.persistence.service.git.GitExecutionWorkspaceService;
import com.datagent.agentv2.persistence.service.git.GitWorkspacePersistenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** V3 的归档、读取与事实装配；raw/LSM 不注册工具或访问插件存储。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
public class ContextResultRuntimeConfiguration {
    @Bean
    ContextPayloadArchive contextPayloadArchive(
            ContextPayloadRepository payloads, StepCompactionProperties properties) {
        properties.validate();
        return new ContextPayloadArchive(payloads, properties.getPayloadMaxBytes());
    }

    @Bean
    ContextResultReader contextResultReader(
            ContextPayloadRepository payloads,
            AgentExecutionService executions,
            ConversationSessionService sessions,
            ContextModelBudgetProvider budgets,
            StepCompactionProperties properties) {
        return new ContextResultReaderAdapter(
                payloads, executions, sessions, budgets, properties.getResultPageMaxBytes());
    }

    @Bean
    ContextResultReadTool contextResultReadTool(ContextResultReader reader, ObjectMapper json) {
        return new ContextResultReadTool(reader, json);
    }

    @Bean
    ContextExecutionFactsFactory contextExecutionFactsFactory(
            GitExecutionWorkspaceService executions,
            GitWorkspacePersistenceService workspaces,
            GitChangePersistenceService changes) {
        return new ContextExecutionFactsFactory(executions, workspaces, changes);
    }
}
