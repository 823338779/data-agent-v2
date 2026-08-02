package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.message.MessageRole;
import com.datagent.agentv2.conversation.message.MessageVisibility;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 承载工具调用前后的独立短事务，确保外部工具执行期间不持有数据库事务。 */
@Service
public class ToolExecutionTransactionService {

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
    private final IdGenerator ids;
    private final ObjectMapper json;

    public ToolExecutionTransactionService(
            AgentToolRegistry registry,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages,
            ConversationSessionService sessions,
            IdGenerator ids,
            ObjectMapper json) {
        this.registry = registry;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
        this.sessions = sessions;
        this.ids = ids;
        this.json = json;
    }

    /** 在独立事务中把 prepared 工具执行推进为 executing，并返回不可变调用输入。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedInvocation beginInvocation(
            AgentExecutionPort.ClaimedAgentExecution run, ToolExecutionEntity candidate) {
        // 重新读取当前执行状态，避免使用循环开始时取得的过期版本。
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

        // 在提交前通过 owner fencing 原子推进状态，并同步内存快照版本。
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
        execution.setStatus(ToolExecutionStatus.EXECUTING.value());
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setExecutionCount(execution.getExecutionCount() + 1);
        execution.setIdempotencyKey(idempotencyKey);
        execution.setVersion(execution.getVersion() + 1);
        return new PreparedInvocation(
                execution, assistant.getConversationSessionId(), arguments, tool);
    }

    /** 在工具调用完成后的独立事务中同时保存 observation 并结束工具执行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistObservation(
            AgentExecutionPort.ClaimedAgentExecution run,
            PreparedInvocation invocation,
            ToolExecutionResult observation) {
        // 先保存 observation 消息，为工具终态更新建立有效外键目标。
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
                        MessageRole.TOOL.value(),
                        MessageVisibility.INTERNAL.value(),
                        execution.getToolExecutionId(),
                        execution.getToolCallId(),
                        execution.getToolName());
        messages.save(message);

        // 使用 begin 事务提交后的版本和 owner fencing 完成工具执行。
        String status =
                observation.status() == ToolExecutionResult.Status.SUCCEEDED
                        ? ToolExecutionStatus.SUCCEEDED.value()
                        : ToolExecutionStatus.FAILED.value();
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
        return ToolExecutionStatus.SUCCEEDED.matches(status)
                || ToolExecutionStatus.FAILED.matches(status);
    }

    /** 已提交 executing 状态对应的工具、参数和版本快照。 */
    record PreparedInvocation(
            ToolExecutionEntity execution,
            String conversationSessionId,
            JsonNode arguments,
            AgentTool tool) {}
}
