package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
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
    private static final String ID_PREFIX_CONVERSATION_MESSAGE = "conversation_message";
    private static final String IDEMPOTENCY_PREFIX = "tool";
    private static final String IDEMPOTENCY_SEPARATOR = ":";
    private static final int TOOL_CALL_DOCUMENT_VERSION = 1;
    private static final String ERROR_INVALID_ASSISTANT_CALL =
            "Prepared tool execution has no matching assistant tool call";

    private final AgentToolRegistry registry;
    private final ToolExecutionService toolExecutions;
    private final ConversationMessageService messages;
    private final ConversationSessionService sessions;
    private final AgentExecutionLeaseService leases;
    private final IdGenerator ids;
    private final ToolFailureSanitizer failures;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutionCoordinator(
            AgentToolRegistry registry,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages,
            ConversationSessionService sessions,
            AgentExecutionLeaseService leases,
            IdGenerator ids,
            ToolFailureSanitizer failures,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
        this.sessions = sessions;
        this.leases = leases;
        this.ids = ids;
        this.failures = failures;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
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
            PreparedInvocation invocation =
                    Objects.requireNonNull(
                            transactions.execute(ignored -> beginInvocation(run, row)));
            ToolExecutionResult observation = invoke(invocation, run, modelInvocationSequence);
            leases.assertOwnedAndWithinBudget(run);
            transactions.executeWithoutResult(
                    ignored -> persistObservation(run, invocation, observation));
            observations.add(observation);
        }
        return List.copyOf(observations);
    }

    private PreparedInvocation beginInvocation(
            AgentExecutionPort.ClaimedAgentExecution run, ToolExecutionEntity candidate) {
        ToolExecutionEntity execution =
                toolExecutions.findByToolExecutionId(candidate.getToolExecutionId());
        if (execution == null || isTerminal(execution.getStatus())) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        ConversationMessage assistant =
                messages.findByConversationMessageId(execution.getAssistantConversationMessageId());
        JsonNode arguments = argumentsFor(assistant, execution);
        AgentTool tool = registry.find(execution.getToolName()).orElse(null);
        String idempotencyKey = idempotencyKey(run, execution, tool);
        Instant startedAt = Instant.now();
        if (!toolExecutions.startExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                startedAt,
                idempotencyKey,
                run.ownerInstanceId(),
                run.executionAttemptNumber())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        execution.setStatus(STATUS_EXECUTING);
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setExecutionCount(execution.getExecutionCount() + 1);
        execution.setIdempotencyKey(idempotencyKey);
        execution.setVersion(execution.getVersion() + 1);
        return new PreparedInvocation(
                execution, assistant.getConversationSessionId(), arguments, tool);
    }

    private ToolExecutionResult invoke(
            PreparedInvocation invocation,
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

    private void persistObservation(
            AgentExecutionPort.ClaimedAgentExecution run,
            PreparedInvocation invocation,
            ToolExecutionResult observation) {
        ToolExecutionEntity execution = invocation.execution();
        String conversationMessageId = ids.next(ID_PREFIX_CONVERSATION_MESSAGE);
        Instant completedAt = Instant.now();
        ConversationMessage message =
                ConversationMessage.observation(
                        conversationMessageId,
                        invocation.conversationSessionId(),
                        run.executionId(),
                        observationJson(observation),
                        sessions.nextConversationMessageSequence(
                                invocation.conversationSessionId()),
                        ROLE_TOOL,
                        VISIBILITY_INTERNAL,
                        execution.getToolExecutionId(),
                        execution.getToolCallId(),
                        execution.getToolName());
        messages.save(message);

        String status =
                observation.status() == ToolExecutionResult.Status.SUCCEEDED
                        ? STATUS_SUCCEEDED
                        : STATUS_FAILED;
        if (!toolExecutions.completeExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                status,
                conversationMessageId,
                completedAt,
                observation.failureCode(),
                run.ownerInstanceId(),
                run.executionAttemptNumber())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
    }

    private JsonNode argumentsFor(ConversationMessage assistant, ToolExecutionEntity execution) {
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
            AgentExecutionPort.ClaimedAgentExecution run,
            ToolExecutionEntity execution,
            AgentTool tool) {
        if (execution.getIdempotencyKey() != null) {
            return execution.getIdempotencyKey();
        }
        if (tool == null || !tool.supportsIdempotency()) {
            return null;
        }
        return String.join(
                IDEMPOTENCY_SEPARATOR,
                IDEMPOTENCY_PREFIX,
                run.executionId(),
                Long.toString(execution.getModelInvocationSequence()),
                execution.getToolCallId());
    }

    private String observationJson(ToolExecutionResult observation) {
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
            ToolExecutionEntity execution,
            String conversationSessionId,
            JsonNode arguments,
            AgentTool tool) {}
}
