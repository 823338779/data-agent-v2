package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.workflow.port.WorkflowExecutionControlPort;
import com.datagent.agentv2.workflow.runtime.WorkflowCancelledException;
import com.datagent.agentv2.workflow.runtime.WorkflowDeadlineExceededException;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 将现有 execution fencing 映射为 Workflow 的取消和截止语义。 */
@Service
@RequiredArgsConstructor
public final class WorkflowExecutionControlAdapter implements WorkflowExecutionControlPort {

    private final LocalExecutionGuard guard;
    private final AgentExecutionService executions;
    private final Clock clock;

    @Override
    public void assertRunning(WorkflowRunRequest request, Instant workflowDeadline) {
        Instant now = clock.instant();
        if (!now.isBefore(workflowDeadline)) {
            throw new WorkflowDeadlineExceededException("数据分析 Workflow 已超过截止时间");
        }
        LocalAgentExecution run =
                new LocalAgentExecution(
                        request.tenantId(),
                        request.conversationSessionId(),
                        request.executionId(),
                        request.inputUpToMessageSequence());
        try {
            guard.assertRunningAndWithinDeadline(run);
        } catch (ExecutionStoppedException stopped) {
            AgentExecution execution = executions.findByExecutionId(request.executionId());
            if (execution != null
                    && execution.getHardDeadlineAt() != null
                    && !now.isBefore(execution.getHardDeadlineAt())) {
                throw new WorkflowDeadlineExceededException("Agent execution 已超过截止时间");
            }
            if (execution != null && execution.getStatus() == ExecutionStatus.CANCELLED) {
                throw new WorkflowCancelledException("用户已取消数据分析 Workflow");
            }
            throw new WorkflowCancelledException("Agent execution 已停止");
        }
    }
}
