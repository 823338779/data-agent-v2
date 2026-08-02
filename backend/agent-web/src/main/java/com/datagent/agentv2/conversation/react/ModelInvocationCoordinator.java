package com.datagent.agentv2.conversation.react;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_EXECUTION_DONE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_CONVERSATION_MESSAGE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.MESSAGE_ROLE_ASSISTANT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.MESSAGE_VISIBILITY_INTERNAL;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.MESSAGE_VISIBILITY_USER;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import com.datagent.agentv2.conversation.cluster.RedisExecutionDeltaBridge;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class ModelInvocationCoordinator {

    private static final String STATUS_STREAMING = "streaming";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_PREPARED = "prepared";
    private static final String ID_PREFIX_MODEL_INVOCATION = "model_invocation";
    private static final int TOOL_CALL_DOCUMENT_VERSION = 1;

    private final ModelInvocationService modelInvocations;
    private final ToolExecutionService toolExecutions;
    private final ConversationMessageService messages;
    private final ConversationSessionService sessions;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionStreamCheckpointService persistedStreamCheckpoints;
    private final ExecutionStreamCheckpointCoordinator streamCheckpointCoordinator;
    private final RedisExecutionDeltaBridge streamDeltaBridge;
    private final AgentExecutionEventService events;
    private final AgentExecutionAttemptService attempts;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final ReactLimitsProperties limits;
    private final TransactionTemplate transactions;

    public ModelInvocationCoordinator(
            ModelInvocationService modelInvocations,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages,
            ConversationSessionService sessions,
            AgentExecutionService agentExecutions,
            AgentExecutionStreamCheckpointService persistedStreamCheckpoints,
            ExecutionStreamCheckpointCoordinator streamCheckpointCoordinator,
            RedisExecutionDeltaBridge streamDeltaBridge,
            AgentExecutionEventService events,
            AgentExecutionAttemptService attempts,
            IdGenerator ids,
            ObjectMapper json,
            ReactLimitsProperties limits,
            PlatformTransactionManager transactionManager) {
        this.modelInvocations = modelInvocations;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
        this.sessions = sessions;
        this.agentExecutions = agentExecutions;
        this.persistedStreamCheckpoints = persistedStreamCheckpoints;
        this.streamCheckpointCoordinator = streamCheckpointCoordinator;
        this.streamDeltaBridge = streamDeltaBridge;
        this.events = events;
        this.attempts = attempts;
        this.ids = ids;
        this.json = json;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ModelInvocationBoundary beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run) {
        return beginModelInvocation(run, null);
    }

    public ModelInvocationBoundary beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run, Long recoveredInputBoundary) {
        return Objects.requireNonNull(
                transactions.execute(
                        ignored -> beginModelInvocationLocked(run, recoveredInputBoundary)));
    }

    private ModelInvocationBoundary beginModelInvocationLocked(
            AgentExecutionPort.ClaimedAgentExecution run, Long recoveredInputBoundary) {
        AgentExecution owned = lockOwned(run);
        long modelInvocationSequence = modelInvocations.nextSequence(run.executionId());
        long inputUpToConversationMessageSequence =
                recoveredInputBoundary == null
                        ? sessions.findByUuid(owned.getConversationSessionId())
                                .getConversationMessageSequence()
                        : recoveredInputBoundary;
        Instant now = Instant.now();
        ModelInvocationEntity modelInvocation = new ModelInvocationEntity();
        modelInvocation.setModelInvocationId(ids.next(ID_PREFIX_MODEL_INVOCATION));
        modelInvocation.setExecutionId(run.executionId());
        modelInvocation.setModelInvocationSequence(modelInvocationSequence);
        modelInvocation.setExecutionAttemptNumber(run.executionAttemptNumber());
        modelInvocation.setInputUpToConversationMessageSequence(
                inputUpToConversationMessageSequence);
        modelInvocation.setStatus(STATUS_STREAMING);
        modelInvocation.setStartedAt(now);
        modelInvocation.setVersion(0L);
        modelInvocations.save(modelInvocation);

        streamCheckpointCoordinator.beginModelInvocation(run, modelInvocationSequence, now);
        return new ModelInvocationBoundary(
                modelInvocationSequence, inputUpToConversationMessageSequence);
    }

    public void checkpoint(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta delta) {
        transactions.executeWithoutResult(
                ignored -> checkpointLocked(run, boundary, delta, Instant.now()));
    }

    private void checkpointLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta delta,
            Instant now) {
        String reasoning = delta.kind() == ModelOutputDelta.Kind.REASONING ? delta.content() : "";
        Instant expiresAt = now.plus(limits.getReasoningRetention());
        ExecutionStreamCheckpointCoordinator.PublishedDelta published =
                streamCheckpointCoordinator.append(
                        run, boundary.modelInvocationSequence(), delta, now);
        streamDeltaBridge.publish(AgentExecutionDeltaNotification.from(published));
        if (!reasoning.isEmpty()) {
            ModelInvocationEntity modelInvocation =
                    modelInvocations.findModelInvocation(
                            run.executionId(), boundary.modelInvocationSequence());
            if (!modelInvocations.appendReasoning(
                    run.executionId(),
                    boundary.modelInvocationSequence(),
                    run.executionAttemptNumber(),
                    modelInvocation.getVersion(),
                    reasoning,
                    expiresAt,
                    now,
                    run.ownerInstanceId())) {
                throw new ExecutionOwnershipLostException(run.executionId());
            }
        }
    }

    public void archiveToolCalls(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            List<ModelToolCall> calls) {
        transactions.executeWithoutResult(
                ignored -> archiveToolCallsLocked(run, boundary, calls, Instant.now()));
    }

    private void archiveToolCallsLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            List<ModelToolCall> calls,
            Instant now) {
        AgentExecution owned = lockOwned(run);
        streamCheckpointCoordinator.flush(
                run,
                boundary.modelInvocationSequence(),
                ExecutionStreamCheckpointCoordinator.Boundary.MODEL_INVOCATION,
                now);
        finishModelInvocation(run, boundary, now);
        ConversationMessage assistant = assistantMessage(owned, MESSAGE_VISIBILITY_INTERNAL, "");
        assistant.attachToolCalls(toolCallsJson(calls));
        messages.save(assistant);

        // assistant 消息先提交到当前事务，再批量建立 prepared execution 外键边界。
        List<ToolExecutionEntity> prepared =
                calls.stream()
                        .map(
                                call ->
                                        preparedExecution(
                                                run,
                                                boundary,
                                                assistant.getConversationMessageId(),
                                                call))
                        .toList();
        toolExecutions.saveBatch(prepared);
    }

    public void completeFinalAnswer(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String answer) {
        transactions.executeWithoutResult(
                ignored -> completeFinalAnswerLocked(run, boundary, answer, Instant.now()));
    }

    private void completeFinalAnswerLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String answer,
            Instant now) {
        AgentExecution owned = lockOwned(run);
        streamCheckpointCoordinator.flush(
                run,
                boundary.modelInvocationSequence(),
                ExecutionStreamCheckpointCoordinator.Boundary.TERMINAL,
                now);
        finishModelInvocation(run, boundary, now);
        AgentExecutionStreamCheckpoint stream =
                persistedStreamCheckpoints.findByExecutionId(run.executionId());
        if (!persistedStreamCheckpoints.complete(
                run.executionId(),
                stream.getVersion(),
                run.executionAttemptNumber(),
                boundary.modelInvocationSequence(),
                run.ownerInstanceId(),
                answer,
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        messages.save(assistantMessage(owned, MESSAGE_VISIBILITY_USER, answer));
        if (!agentExecutions.completeOwned(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        attempts.finish(
                run.executionId(), run.executionAttemptNumber(), STATUS_COMPLETED, now, null);
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        owned.getConversationSessionId(),
                        run.executionId(),
                        sessions.nextExecutionEventSequence(owned.getConversationSessionId()),
                        EVENT_EXECUTION_DONE,
                        null,
                        now));
    }

    private void finishModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            Instant now) {
        ModelInvocationEntity modelInvocation =
                modelInvocations.findModelInvocation(
                        run.executionId(), boundary.modelInvocationSequence());
        if (modelInvocation == null
                || !modelInvocations.finishOwned(
                        run.executionId(),
                        boundary.modelInvocationSequence(),
                        run.executionAttemptNumber(),
                        modelInvocation.getVersion(),
                        STATUS_COMPLETED,
                        now,
                        run.ownerInstanceId())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
    }

    private ConversationMessage assistantMessage(
            AgentExecution run, String visibility, String content) {
        return ConversationMessage.assistant(
                ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                run.getConversationSessionId(),
                run.getExecutionId(),
                content,
                sessions.nextConversationMessageSequence(run.getConversationSessionId()),
                MESSAGE_ROLE_ASSISTANT,
                visibility);
    }

    private ToolExecutionEntity preparedExecution(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String assistantConversationMessageId,
            ModelToolCall call) {
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId(ids.next("tool_execution"));
        execution.setExecutionId(run.executionId());
        execution.setModelInvocationSequence(boundary.modelInvocationSequence());
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setAssistantConversationMessageId(assistantConversationMessageId);
        execution.setToolCallId(call.toolCallId());
        execution.setToolName(call.name());
        execution.setStatus(STATUS_PREPARED);
        execution.setExecutionCount(0);
        execution.setVersion(0L);
        return execution;
    }

    private String toolCallsJson(List<ModelToolCall> calls) {
        ObjectNode root = json.createObjectNode();
        root.put("version", TOOL_CALL_DOCUMENT_VERSION);
        ArrayNode array = root.putArray("calls");
        for (ModelToolCall call : calls) {
            ObjectNode item = array.addObject();
            item.put("id", call.toolCallId());
            item.put("name", call.name());
            item.set("arguments", call.arguments());
        }
        return json.writeValueAsString(root);
    }

    private AgentExecution lockOwned(AgentExecutionPort.ClaimedAgentExecution run) {
        AgentExecution owned = agentExecutions.lockById(run.executionId());
        if (owned == null
                || !"running".equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttemptNumber() != owned.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        return owned;
    }

    public record ModelInvocationBoundary(
            long modelInvocationSequence, long inputUpToConversationMessageSequence) {}
}
