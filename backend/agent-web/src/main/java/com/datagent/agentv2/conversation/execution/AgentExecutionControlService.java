package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.conversation.runtime.LocalExecutionCancelled;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AgentExecutionControlService {

    private static final Set<String> TERMINAL = Set.of("done", "cancelled", "failed");

    private final ConversationSessionService sessions;
    private final AgentExecutionService executions;
    private final AgentExecutionEventService events;
    private final AgentExecutionProcessTerminationService termination;
    private final ApplicationEventPublisher applicationEvents;
    private final IdGenerator ids;

    @Transactional
    public AgentExecution cancel(String sessionId, String executionId) {
        AgentExecution execution = requiredLockedExecution(sessionId, executionId);
        if (TERMINAL.contains(execution.getStatus())) {
            return execution;
        }
        Instant now = Instant.now();
        termination.cancelLocked(execution, now);
        execution.setStatus(ExecutionStatus.CANCELLED.value());
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        sessionId,
                        executionId,
                        sessions.nextExecutionEventSequence(sessionId),
                        ExecutionEventType.CANCELLED.value(),
                        now));
        applicationEvents.publishEvent(new LocalExecutionCancelled(executionId));
        return execution;
    }

    private AgentExecution requiredLockedExecution(String sessionId, String executionId) {
        AgentExecution execution = executions.lockById(executionId);
        if (execution == null || !sessionId.equals(execution.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return execution;
    }
}
