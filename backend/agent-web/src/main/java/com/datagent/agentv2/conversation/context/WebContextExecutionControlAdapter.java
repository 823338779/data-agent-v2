package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 上下文压缩边界只校验本机 execution 仍运行且未超时。 */
@Component
@RequiredArgsConstructor
public class WebContextExecutionControlAdapter implements ContextExecutionControlPort {

    private final AgentExecutionService executions;

    @Override
    @Transactional
    public void assertRunningAndWithinDeadline(ContextExecutionRef execution) {
        AgentExecution locked = executions.lockById(execution.executionId());
        boolean running =
                TenantContext.requiredTenantId().equals(execution.tenantId())
                        && locked != null
                        && execution
                                .conversationSessionId()
                                .equals(locked.getConversationSessionId())
                        && "running".equals(locked.getStatus())
                        && locked.getHardDeadlineAt() != null
                        && locked.getHardDeadlineAt().isAfter(Instant.now());
        if (!running) {
            throw new ExecutionStoppedException(execution.executionId());
        }
    }
}
