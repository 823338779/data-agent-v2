package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 从当前持久化 execution 边界构建 Workflow 输入，不读取未来消息或 Tool payload。 */
@Component
@RequiredArgsConstructor
public final class WorkflowInputFactory {

    private static final int MAX_RECENT_TURNS = 8;
    private static final int MAX_RECENT_TURN_CHARACTERS = 2_000;

    private final AgentExecutionService executions;
    private final ConversationMessageService messages;

    public WorkflowRunRequest create(LocalAgentExecution run) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null || execution.getHardDeadlineAt() == null) {
            throw new IllegalStateException("Agent execution was not found");
        }
        List<ConversationMessage> visible =
                messages.findByConversationSessionIdUpTo(
                        run.conversationSessionId(), run.inputUpToMessageSequence());
        ConversationMessage question =
                visible.stream()
                        .filter(message -> message.getRole() == MessageRole.USER)
                        .reduce((first, second) -> second)
                        .orElseThrow(
                                () -> new IllegalStateException("Workflow question was not found"));
        List<String> recentTurns =
                visible.stream()
                        .filter(
                                message ->
                                        message.getContent() != null
                                                && !message.getContent().isBlank())
                        .skip(Math.max(0, visible.size() - MAX_RECENT_TURNS))
                        .map(
                                message ->
                                        message.getRole().value()
                                                + ": "
                                                + truncate(message.getContent()))
                        .toList();
        return new WorkflowRunRequest(
                run.tenantId(),
                run.conversationSessionId(),
                run.executionId(),
                run.inputUpToMessageSequence(),
                question.getContent(),
                recentTurns,
                execution.getHardDeadlineAt());
    }

    private static String truncate(String value) {
        return value.length() <= MAX_RECENT_TURN_CHARACTERS
                ? value
                : value.substring(0, MAX_RECENT_TURN_CHARACTERS);
    }
}
