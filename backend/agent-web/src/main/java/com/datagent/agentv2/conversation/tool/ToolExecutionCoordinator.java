package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class ToolExecutionCoordinator implements AutoCloseable {

    private static final String STATUS_PREPARED = "prepared";
    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String ROLE_TOOL = "tool";
    private static final String VISIBILITY_INTERNAL = "internal";
    private static final String ID_PREFIX_MESSAGE = "message";
    private static final String IDEMPOTENCY_PREFIX = "tool";
    private static final String IDEMPOTENCY_SEPARATOR = ":";
    private static final int TOOL_CALL_DOCUMENT_VERSION = 1;
    private static final String ERROR_INVALID_ASSISTANT_CALL =
            "Prepared tool execution has no matching assistant tool call";

    private final AgentToolRegistry registry;
    private final AgentToolExecutionService executions;
    private final AgentMessageService messages;
    private final AgentSessionService sessions;
    private final RunLeaseService leases;
    private final IdGenerator ids;
    private final ToolFailureSanitizer failures;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutionCoordinator(
            AgentToolRegistry registry,
            AgentToolExecutionService executions,
            AgentMessageService messages,
            AgentSessionService sessions,
            RunLeaseService leases,
            IdGenerator ids,
            ToolFailureSanitizer failures,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.executions = executions;
        this.messages = messages;
        this.sessions = sessions;
        this.leases = leases;
        this.ids = ids;
        this.failures = failures;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<ToolObservation> executeTurn(
            RunExecutionPort.OwnedRun run, long modelTurnSequence) {
        List<ToolObservation> observations = new ArrayList<>();

        // Model Turn 内严格按 prepared 顺序执行；单个失败仅形成 Observation，不中断后续工具。
        for (AgentToolExecutionEntity row : executions.findByTurn(run.runId(), modelTurnSequence)) {
            if (isTerminal(row.getStatus())) {
                continue;
            }
            leases.assertOwnedAndWithinBudget(run);
            PreparedInvocation invocation =
                    Objects.requireNonNull(
                            transactions.execute(ignored -> beginInvocation(run, row)));
            ToolObservation observation = invoke(invocation, run, modelTurnSequence);
            leases.assertOwnedAndWithinBudget(run);
            transactions.executeWithoutResult(
                    ignored -> persistObservation(run, invocation, observation));
            observations.add(observation);
        }
        return List.copyOf(observations);
    }

    private PreparedInvocation beginInvocation(
            RunExecutionPort.OwnedRun run, AgentToolExecutionEntity candidate) {
        AgentToolExecutionEntity execution =
                executions.findByToolExecutionId(candidate.getToolExecutionId());
        if (execution == null || isTerminal(execution.getStatus())) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        AgentMessageEntity assistant = messages.findByMessageId(execution.getAssistantMessageId());
        JsonNode arguments = argumentsFor(assistant, execution);
        AgentTool tool = registry.find(execution.getToolName()).orElse(null);
        String idempotencyKey = idempotencyKey(run, execution, tool);
        Instant startedAt = Instant.now();
        if (!executions.startExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                startedAt,
                idempotencyKey,
                run.ownerInstanceId(),
                run.executionAttempt())) {
            throw new RunOwnershipLostException(run.runId());
        }
        execution.setStatus(STATUS_EXECUTING);
        execution.setExecutionAttempt(run.executionAttempt());
        execution.setExecutionCount(execution.getExecutionCount() + 1);
        execution.setIdempotencyKey(idempotencyKey);
        execution.setVersion(execution.getVersion() + 1);
        return new PreparedInvocation(execution, assistant.getSessionId(), arguments, tool);
    }

    private ToolObservation invoke(
            PreparedInvocation invocation, RunExecutionPort.OwnedRun run, long modelTurnSequence) {
        if (invocation.tool() == null) {
            return failures.sanitize(new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL));
        }
        AgentTool.InvocationContext context =
                new AgentTool.InvocationContext(
                        run.tenantId(),
                        run.runId(),
                        modelTurnSequence,
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
            return ToolObservation.succeeded(content);
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

    private void persistObservation(
            RunExecutionPort.OwnedRun run,
            PreparedInvocation invocation,
            ToolObservation observation) {
        AgentToolExecutionEntity execution = invocation.execution();
        String messageId = ids.next(ID_PREFIX_MESSAGE);
        Instant completedAt = Instant.now();
        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId(messageId);
        message.setSessionId(invocation.sessionId());
        message.setRunId(run.runId());
        message.setRole(ROLE_TOOL);
        message.setContent(observationJson(observation));
        message.setSequence(sessions.nextMessageSequence(invocation.sessionId()));
        message.setToolExecutionId(execution.getToolExecutionId());
        message.setToolCallId(execution.getToolCallId());
        message.setToolName(execution.getToolName());
        message.setVisibility(VISIBILITY_INTERNAL);
        message.setVersion(0L);
        messages.save(message);

        String status =
                observation.status() == ToolObservation.Status.SUCCEEDED
                        ? STATUS_SUCCEEDED
                        : STATUS_FAILED;
        if (!executions.completeExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                status,
                messageId,
                completedAt,
                observation.failureCode(),
                run.ownerInstanceId(),
                run.executionAttempt())) {
            throw new RunOwnershipLostException(run.runId());
        }
    }

    private JsonNode argumentsFor(
            AgentMessageEntity assistant, AgentToolExecutionEntity execution) {
        if (assistant == null || assistant.getToolCalls() == null) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        try {
            JsonNode root = json.readTree(assistant.getToolCalls());
            if (root.path("version").asInt() != TOOL_CALL_DOCUMENT_VERSION) {
                throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
            }
            for (JsonNode call : root.path("calls")) {
                if (execution.getToolCallId().equals(call.path("id").asString())
                        && execution.getToolName().equals(call.path("name").asString())
                        && call.path("arguments").isObject()) {
                    return call.path("arguments").deepCopy();
                }
            }
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL, failure);
        }
    }

    private static String idempotencyKey(
            RunExecutionPort.OwnedRun run, AgentToolExecutionEntity execution, AgentTool tool) {
        if (execution.getIdempotencyKey() != null) {
            return execution.getIdempotencyKey();
        }
        if (tool == null || !tool.supportsIdempotency()) {
            return null;
        }
        return String.join(
                IDEMPOTENCY_SEPARATOR,
                IDEMPOTENCY_PREFIX,
                run.runId(),
                Long.toString(execution.getModelTurnSequence()),
                execution.getToolCallId());
    }

    private String observationJson(ToolObservation observation) {
        ObjectNode root = json.createObjectNode();
        root.put("status", observation.status().name().toLowerCase(java.util.Locale.ROOT));
        if (observation.content() != null) {
            root.set("content", observation.content());
        } else {
            ObjectNode failure = root.putObject("failure");
            failure.put("code", observation.failureCode());
            failure.put("message", observation.failureMessage());
        }
        return json.writeValueAsString(root);
    }

    private static boolean isTerminal(String status) {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status);
    }

    @Override
    @PreDestroy
    public void close() {
        toolExecutor.shutdownNow();
    }

    private record PreparedInvocation(
            AgentToolExecutionEntity execution,
            String sessionId,
            JsonNode arguments,
            AgentTool tool) {}
}
