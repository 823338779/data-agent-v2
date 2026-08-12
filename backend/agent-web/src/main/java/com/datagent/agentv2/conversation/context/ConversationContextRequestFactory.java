package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 从已持久化 invocation 边界、当前工具集和服务配置建立策略请求。 */
@Component
public class ConversationContextRequestFactory {

    private static final int POLICY_VERSION = 1;
    private static final int BASE_SEGMENT_TURNS = 10;
    private static final int COMPRESSION_THRESHOLD_PERCENT = 75;
    private static final int TARGET_WATERMARK_PERCENT = 65;
    private static final int WARM_TARGET_TURNS = 100;
    private static final int WARM_MAX_TURNS = 109;
    private static final int PROTECTED_HOT_TURNS = 10;
    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";

    private final AgentExecutionService executions;
    private final ContextModelBudgetProvider budgets;
    private final int fanout;
    private final int maxLevel;

    public ConversationContextRequestFactory(
            AgentExecutionService executions,
            ContextModelBudgetProvider budgets,
            @Value("${data-agent.context.compaction.fanout:5}") int fanout,
            @Value("${data-agent.context.compaction.max-level:4}") int maxLevel) {
        this.executions = executions;
        this.budgets = budgets;
        this.fanout = fanout;
        this.maxLevel = maxLevel;
    }

    public ConversationContextRequest create(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationCoordinator.ModelInvocationBoundary boundary,
            List<ModelToolDefinition> tools) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }

        // 当前 provider 没有独立 system 消息；该列表保留真实调用边界，未来出现时可原样传入。
        List<ContextMessage> fixedSystemMessages = List.of();
        List<ContextToolDefinition> contextTools =
                tools.stream()
                        .map(
                                tool ->
                                        new ContextToolDefinition(
                                                tool.name(), tool.description(), tool.parameters()))
                        .toList();
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        run.tenantId(),
                        execution.getConversationSessionId(),
                        run.executionId(),
                        run.ownerInstanceId(),
                        run.executionAttemptNumber(),
                        boundary.inputUpToConversationMessageSequence()),
                budgets.currentBudget(),
                new ContextPolicySnapshot(
                        POLICY_VERSION,
                        fanout,
                        maxLevel,
                        BASE_SEGMENT_TURNS,
                        COMPRESSION_THRESHOLD_PERCENT,
                        TARGET_WATERMARK_PERCENT,
                        WARM_TARGET_TURNS,
                        WARM_MAX_TURNS,
                        PROTECTED_HOT_TURNS),
                fixedSystemMessages,
                contextTools);
    }
}
