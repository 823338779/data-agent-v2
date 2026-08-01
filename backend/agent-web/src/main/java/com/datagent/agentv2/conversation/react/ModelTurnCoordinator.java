package com.datagent.agentv2.conversation.react;

import static com.datagent.agentv2.conversation.RunConstants.EVENT_RUN_DONE;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_EVENT;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_MESSAGE;
import static com.datagent.agentv2.conversation.RunConstants.MESSAGE_ROLE_ASSISTANT;
import static com.datagent.agentv2.conversation.RunConstants.MESSAGE_VISIBILITY_INTERNAL;
import static com.datagent.agentv2.conversation.RunConstants.MESSAGE_VISIBILITY_USER;

import com.datagent.agentv2.conversation.cluster.RedisStreamDeltaBridge;
import com.datagent.agentv2.conversation.cluster.StreamDeltaEnvelope;
import com.datagent.agentv2.conversation.model.ModelDelta;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.conversation.stream.RunStreamCheckpointService;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentModelTurnEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentModelTurnService;
import com.datagent.agentv2.persistence.service.AgentRunAttemptService;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
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
public final class ModelTurnCoordinator {

    private static final String STATUS_STREAMING = "streaming";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_PREPARED = "prepared";
    private static final String ID_PREFIX_MODEL_TURN = "model_turn";
    private static final int TOOL_CALL_DOCUMENT_VERSION = 1;

    private final AgentModelTurnService modelTurns;
    private final AgentToolExecutionService executions;
    private final AgentMessageService messages;
    private final AgentSessionService sessions;
    private final AgentRunService runs;
    private final AgentRunStreamService streams;
    private final RunStreamCheckpointService streamCheckpoints;
    private final RedisStreamDeltaBridge streamDeltaBridge;
    private final AgentRunEventService events;
    private final AgentRunAttemptService attempts;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final ReactLimitsProperties limits;
    private final TransactionTemplate transactions;

    public ModelTurnCoordinator(
            AgentModelTurnService modelTurns,
            AgentToolExecutionService executions,
            AgentMessageService messages,
            AgentSessionService sessions,
            AgentRunService runs,
            AgentRunStreamService streams,
            RunStreamCheckpointService streamCheckpoints,
            RedisStreamDeltaBridge streamDeltaBridge,
            AgentRunEventService events,
            AgentRunAttemptService attempts,
            IdGenerator ids,
            ObjectMapper json,
            ReactLimitsProperties limits,
            PlatformTransactionManager transactionManager) {
        this.modelTurns = modelTurns;
        this.executions = executions;
        this.messages = messages;
        this.sessions = sessions;
        this.runs = runs;
        this.streams = streams;
        this.streamCheckpoints = streamCheckpoints;
        this.streamDeltaBridge = streamDeltaBridge;
        this.events = events;
        this.attempts = attempts;
        this.ids = ids;
        this.json = json;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public TurnBoundary beginTurn(RunExecutionPort.OwnedRun run) {
        return beginTurn(run, null);
    }

    public TurnBoundary beginTurn(RunExecutionPort.OwnedRun run, Long recoveredInputBoundary) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> beginTurnLocked(run, recoveredInputBoundary)));
    }

    private TurnBoundary beginTurnLocked(
            RunExecutionPort.OwnedRun run, Long recoveredInputBoundary) {
        AgentRunEntity owned = lockOwned(run);
        long sequence = modelTurns.nextSequence(run.runId());
        long boundary =
                recoveredInputBoundary == null
                        ? sessions.findByUuid(owned.getSessionId()).getMessageSequence()
                        : recoveredInputBoundary;
        Instant now = Instant.now();
        AgentModelTurnEntity turn = new AgentModelTurnEntity();
        turn.setModelTurnId(ids.next(ID_PREFIX_MODEL_TURN));
        turn.setRunId(run.runId());
        turn.setModelTurnSequence(sequence);
        turn.setExecutionAttempt(run.executionAttempt());
        turn.setInputUpToMessageSequence(boundary);
        turn.setStatus(STATUS_STREAMING);
        turn.setStartedAt(now);
        turn.setVersion(0L);
        modelTurns.save(turn);

        streamCheckpoints.beginTurn(run, sequence, now);
        return new TurnBoundary(sequence, boundary);
    }

    public void checkpoint(RunExecutionPort.OwnedRun run, TurnBoundary boundary, ModelDelta delta) {
        transactions.executeWithoutResult(
                ignored -> checkpointLocked(run, boundary, delta, Instant.now()));
    }

    private void checkpointLocked(
            RunExecutionPort.OwnedRun run, TurnBoundary boundary, ModelDelta delta, Instant now) {
        String reasoning = delta.kind() == ModelDelta.Kind.REASONING ? delta.content() : "";
        Instant expiresAt = now.plus(limits.getReasoningRetention());
        RunStreamCheckpointService.PublishedDelta published =
                streamCheckpoints.append(run, boundary.modelTurnSequence(), delta, now);
        streamDeltaBridge.publish(StreamDeltaEnvelope.from(published));
        if (!reasoning.isEmpty()) {
            AgentModelTurnEntity turn =
                    modelTurns.findTurn(run.runId(), boundary.modelTurnSequence());
            if (!modelTurns.appendReasoning(
                    run.runId(),
                    boundary.modelTurnSequence(),
                    run.executionAttempt(),
                    turn.getVersion(),
                    reasoning,
                    expiresAt,
                    now,
                    run.ownerInstanceId())) {
                throw new RunOwnershipLostException(run.runId());
            }
        }
    }

    public void archiveToolCalls(
            RunExecutionPort.OwnedRun run, TurnBoundary boundary, List<ToolCallDocument> calls) {
        transactions.executeWithoutResult(
                ignored -> archiveToolCallsLocked(run, boundary, calls, Instant.now()));
    }

    private void archiveToolCallsLocked(
            RunExecutionPort.OwnedRun run,
            TurnBoundary boundary,
            List<ToolCallDocument> calls,
            Instant now) {
        AgentRunEntity owned = lockOwned(run);
        streamCheckpoints.flush(
                run, boundary.modelTurnSequence(), RunStreamCheckpointService.Boundary.TURN, now);
        finishTurn(run, boundary, now);
        AgentMessageEntity assistant = assistantMessage(owned, MESSAGE_VISIBILITY_INTERNAL, "");
        assistant.setToolCalls(toolCallsJson(calls));
        messages.save(assistant);

        // assistant 消息先提交到当前事务，再批量建立 prepared execution 外键边界。
        List<AgentToolExecutionEntity> prepared =
                calls.stream()
                        .map(
                                call ->
                                        preparedExecution(
                                                run, boundary, assistant.getMessageId(), call))
                        .toList();
        executions.saveBatch(prepared);
    }

    public void completeFinalAnswer(
            RunExecutionPort.OwnedRun run, TurnBoundary boundary, String answer) {
        transactions.executeWithoutResult(
                ignored -> completeFinalAnswerLocked(run, boundary, answer, Instant.now()));
    }

    private void completeFinalAnswerLocked(
            RunExecutionPort.OwnedRun run, TurnBoundary boundary, String answer, Instant now) {
        AgentRunEntity owned = lockOwned(run);
        streamCheckpoints.flush(
                run,
                boundary.modelTurnSequence(),
                RunStreamCheckpointService.Boundary.TERMINAL,
                now);
        finishTurn(run, boundary, now);
        AgentRunStreamEntity stream = streams.findByRunId(run.runId());
        if (!streams.complete(
                run.runId(),
                stream.getVersion(),
                run.executionAttempt(),
                boundary.modelTurnSequence(),
                run.ownerInstanceId(),
                answer,
                now)) {
            throw new RunOwnershipLostException(run.runId());
        }
        messages.save(assistantMessage(owned, MESSAGE_VISIBILITY_USER, answer));
        if (!runs.completeOwned(run.runId(), run.ownerInstanceId(), run.executionAttempt(), now)) {
            throw new RunOwnershipLostException(run.runId());
        }
        attempts.finish(run.runId(), run.executionAttempt(), STATUS_COMPLETED, now, null);
        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setEventId(ids.next(ID_PREFIX_EVENT));
        event.setSessionId(owned.getSessionId());
        event.setRunId(run.runId());
        event.setSequence(sessions.nextEventSequence(owned.getSessionId()));
        event.setType(EVENT_RUN_DONE);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);
    }

    private void finishTurn(RunExecutionPort.OwnedRun run, TurnBoundary boundary, Instant now) {
        AgentModelTurnEntity turn = modelTurns.findTurn(run.runId(), boundary.modelTurnSequence());
        if (turn == null
                || !modelTurns.finishOwned(
                        run.runId(),
                        boundary.modelTurnSequence(),
                        run.executionAttempt(),
                        turn.getVersion(),
                        STATUS_COMPLETED,
                        now,
                        run.ownerInstanceId())) {
            throw new RunOwnershipLostException(run.runId());
        }
    }

    private AgentMessageEntity assistantMessage(
            AgentRunEntity run, String visibility, String content) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId(ids.next(ID_PREFIX_MESSAGE));
        message.setSessionId(run.getSessionId());
        message.setRunId(run.getRunId());
        message.setRole(MESSAGE_ROLE_ASSISTANT);
        message.setContent(content);
        message.setSequence(sessions.nextMessageSequence(run.getSessionId()));
        message.setVisibility(visibility);
        message.setVersion(0L);
        return message;
    }

    private AgentToolExecutionEntity preparedExecution(
            RunExecutionPort.OwnedRun run,
            TurnBoundary boundary,
            String assistantMessageId,
            ToolCallDocument call) {
        AgentToolExecutionEntity execution = new AgentToolExecutionEntity();
        execution.setToolExecutionId(ids.next("tool_execution"));
        execution.setRunId(run.runId());
        execution.setModelTurnSequence(boundary.modelTurnSequence());
        execution.setExecutionAttempt(run.executionAttempt());
        execution.setAssistantMessageId(assistantMessageId);
        execution.setToolCallId(call.id());
        execution.setToolName(call.name());
        execution.setStatus(STATUS_PREPARED);
        execution.setExecutionCount(0);
        execution.setVersion(0L);
        return execution;
    }

    private String toolCallsJson(List<ToolCallDocument> calls) {
        ObjectNode root = json.createObjectNode();
        root.put("version", TOOL_CALL_DOCUMENT_VERSION);
        ArrayNode array = root.putArray("calls");
        for (ToolCallDocument call : calls) {
            ObjectNode item = array.addObject();
            item.put("id", call.id());
            item.put("name", call.name());
            item.set("arguments", call.arguments());
        }
        return json.writeValueAsString(root);
    }

    private AgentRunEntity lockOwned(RunExecutionPort.OwnedRun run) {
        AgentRunEntity owned = runs.lockById(run.runId());
        if (owned == null
                || !"running".equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttempt() != owned.getExecutionAttempt()) {
            throw new RunOwnershipLostException(run.runId());
        }
        return owned;
    }

    public record TurnBoundary(long modelTurnSequence, long inputUpToMessageSequence) {}
}
