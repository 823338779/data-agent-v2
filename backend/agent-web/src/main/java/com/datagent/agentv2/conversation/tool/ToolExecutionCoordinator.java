package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.enums.ToolExecutionStatus;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class ToolExecutionCoordinator implements AutoCloseable {

    private static final String ERROR_INVALID_ASSISTANT_CALL =
            "Prepared tool execution has no matching assistant tool call";

    private final ToolExecutionService toolExecutions;
    private final AgentExecutionLeaseService leases;
    private final ToolExecutionTransactionService transactionOperations;
    private final ToolFailureSanitizer failures;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutionCoordinator(
            ToolExecutionService toolExecutions,
            AgentExecutionLeaseService leases,
            ToolExecutionTransactionService transactionOperations,
            ToolFailureSanitizer failures) {
        this.toolExecutions = toolExecutions;
        this.leases = leases;
        this.transactionOperations = transactionOperations;
        this.failures = failures;
    }

    public List<ToolExecutionResult> executeModelInvocationTools(
            AgentExecutionPort.ClaimedAgentExecution run, long modelInvocationSequence) {
        List<ToolExecutionResult> observations = new ArrayList<>();

        // Model Turn 内严格按 prepared 顺序执行；单个失败仅形成 Observation，不中断后续工具。
        for (ToolExecutionEntity row :
                toolExecutions.findByTurn(run.executionId(), modelInvocationSequence)) {
            if (isTerminal(row.getStatus())) {
                continue;
            }
            leases.assertOwnedAndWithinBudget(run);
            ToolExecutionTransactionService.PreparedInvocation invocation =
                    transactionOperations.beginInvocation(run, row);
            ToolExecutionResult observation = invoke(invocation, run, modelInvocationSequence);
            leases.assertOwnedAndWithinBudget(run);
            transactionOperations.persistObservation(run, invocation, observation);
            observations.add(observation);
        }
        return List.copyOf(observations);
    }

    private ToolExecutionResult invoke(
            ToolExecutionTransactionService.PreparedInvocation invocation,
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence) {
        if (invocation.tool() == null) {
            return failures.sanitize(new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL));
        }
        AgentTool.InvocationContext context =
                new AgentTool.InvocationContext(
                        run.tenantId(),
                        run.executionId(),
                        modelInvocationSequence,
                        invocation.execution().getToolCallId(),
                        invocation.execution().getIdempotencyKey());
        Future<JsonNode> future =
                toolExecutor.submit(
                        () -> invocation.tool().execute(invocation.arguments(), context));
        try {
            JsonNode content =
                    future.get(invocation.tool().timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (content == null) {
                throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
            }
            return ToolExecutionResult.succeeded(content);
        } catch (TimeoutException failure) {
            future.cancel(true);
            return failures.sanitize(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failures.sanitize(failure);
        } catch (ExecutionException failure) {
            return failures.sanitize(failure.getCause());
        }
    }

    private static boolean isTerminal(String status) {
        return ToolExecutionStatus.SUCCEEDED.matches(status)
                || ToolExecutionStatus.FAILED.matches(status);
    }

    @Override
    @PreDestroy
    public void close() {
        toolExecutor.shutdownNow();
    }
}
