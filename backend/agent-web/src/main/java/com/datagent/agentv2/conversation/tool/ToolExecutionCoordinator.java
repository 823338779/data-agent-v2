package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public final class ToolExecutionCoordinator implements AutoCloseable {

    private static final String ERROR_INVALID_RESULT = "Tool returned no result";

    private final ExecutionToolViews toolViews;
    private final LocalExecutionGuard guard;
    private final ConversationProcessCoordinator processes;
    private final ToolFailureSanitizer failures;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public List<ToolExecutionResult> executeModelInvocationTools(
            LocalAgentExecution run,
            long modelInvocationSequence,
            List<PreparedToolInvocation> prepared) {
        List<ToolExecutionResult> observations = new ArrayList<>();
        for (PreparedToolInvocation invocation : prepared) {
            guard.assertRunningAndWithinDeadline(run);
            processes.startTool(run, invocation.processItemId(), Instant.now());
            ToolExecutionResult result = invoke(run, modelInvocationSequence, invocation);
            guard.assertRunningAndWithinDeadline(run);
            processes.finishTool(run, invocation.processItemId(), result, Instant.now());
            observations.add(result);
        }
        return List.copyOf(observations);
    }

    private ToolExecutionResult invoke(
            LocalAgentExecution run,
            long modelInvocationSequence,
            PreparedToolInvocation invocation) {
        AgentTool tool;
        try {
            tool =
                    toolViews
                            .require(run.executionId())
                            .requireVisible(invocation.call().name())
                            .tool();
        } catch (Exception failure) {
            return failures.sanitize(failure);
        }
        AgentTool.InvocationContext context =
                new AgentTool.InvocationContext(
                        run.tenantId(),
                        run.executionId(),
                        modelInvocationSequence,
                        invocation.call().toolCallId(),
                        run.executionId() + ":" + invocation.call().toolCallId());
        Future<JsonNode> future =
                toolExecutor.submit(() -> tool.execute(invocation.call().arguments(), context));
        try {
            JsonNode content = future.get(tool.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (content == null) {
                throw new IllegalStateException(ERROR_INVALID_RESULT);
            }
            return ToolExecutionResult.succeeded(content);
        } catch (TimeoutException failure) {
            future.cancel(true);
            return failures.sanitize(failure);
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failures.sanitize(failure);
        } catch (ExecutionException failure) {
            return failures.sanitize(failure.getCause());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        toolExecutor.shutdownNow();
    }
}
