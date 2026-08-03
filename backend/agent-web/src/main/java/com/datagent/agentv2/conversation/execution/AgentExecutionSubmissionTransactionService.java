package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_SESSION_CHANGED_CONCURRENTLY;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_CONVERSATION_MESSAGE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_STREAM_CHECKPOINT;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.message.MessageRole;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 承载消息提交中的独立事务边界，避免冲突重放加入已经失败的创建事务。 */
@Service
public class AgentExecutionSubmissionTransactionService {

    private static final ExecutionRequestType REQUEST_TYPE =
            ExecutionRequestType.MESSAGE_SUBMISSION;
    private static final long DEFAULT_EXECUTION_BUDGET_MS = 600_000L;

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionEventService events;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final IdGenerator ids;

    public AgentExecutionSubmissionTransactionService(
            ConversationSessionService sessions,
            ConversationMessageService messages,
            AgentExecutionService agentExecutions,
            AgentExecutionEventService events,
            AgentExecutionStreamCheckpointService streamCheckpoints,
            IdGenerator ids) {
        this.sessions = sessions;
        this.messages = messages;
        this.agentExecutions = agentExecutions;
        this.events = events;
        this.streamCheckpoints = streamCheckpoints;
        this.ids = ids;
    }

    /** 在独立事务内创建提交记录，或者返回已经存在的幂等提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConversationSession createOrReplay(
            String conversationSessionId,
            String content,
            String idempotencyKey,
            String payloadHash,
            String requestId) {
        // 锁定会话并在写入前处理串行幂等重放和活动执行冲突。
        ConversationSession session = sessions.lockById(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        AgentExecution replay =
                agentExecutions.findRequest(
                        conversationSessionId, REQUEST_TYPE.value(), idempotencyKey);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return session;
        }
        if (agentExecutions.hasActiveExecution(conversationSessionId)) {
            throw new ActiveExecutionConflictException();
        }

        // 在创建依赖记录前推进由会话持有的消息和事件序号。
        ConversationSession.AllocatedSequences sequences = session.allocateSubmissionSequences();
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }

        // 先持久化用户消息，因为执行记录需要引用该触发消息。
        String operatorUserId = OperatorContext.requiredOperator().userId();
        ConversationMessage message =
                ConversationMessage.user(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        conversationSessionId,
                        content,
                        sequences.conversationMessage(),
                        operatorUserId,
                        MessageRole.USER.value());
        messages.save(message);

        // 创建排队中的执行记录，并补全消息到执行的双向关联。
        Instant now = Instant.now();
        AgentExecution run =
                AgentExecution.queued(
                        ids.next(ID_PREFIX_EXECUTION),
                        conversationSessionId,
                        message.getConversationMessageId(),
                        operatorUserId,
                        requestId,
                        now,
                        REQUEST_TYPE.value(),
                        idempotencyKey,
                        payloadHash,
                        DEFAULT_EXECUTION_BUDGET_MS,
                        ExecutionStatus.QUEUED.value(),
                        ExecutionPhase.QUEUED.value());
        agentExecutions.save(run);

        message.associateExecution(run.getExecutionId());
        messages.updateById(message);

        // 初始化可重放事件流和模型输出检查点，使执行在提交后可被 worker 接管。
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        conversationSessionId,
                        run.getExecutionId(),
                        sequences.executionEvent(),
                        ExecutionEventType.QUEUED.value(),
                        null,
                        now));
        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT),
                        run.getExecutionId(),
                        ExecutionPhase.QUEUED.value(),
                        EMPTY_TEXT));
        return session;
    }

    /** 在创建事务回滚后，以新事务读取并发请求已经提交的幂等结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ConversationSession replayAfterConstraintConflict(
            String conversationSessionId, String idempotencyKey, String payloadHash) {
        // 仅把相同幂等键和相同负载视为可重放结果。
        AgentExecution replay =
                agentExecutions.findRequest(
                        conversationSessionId, REQUEST_TYPE.value(), idempotencyKey);
        if (replay == null) {
            return null;
        }
        requireSamePayload(replay, payloadHash);

        // 返回租户过滤后的会话快照，供应用层继续构建响应。
        ConversationSession session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void requireSamePayload(AgentExecution run, String payloadHash) {
        if (!payloadHash.equals(run.getRequestPayloadHash())) {
            throw new IdempotencyKeyReusedException();
        }
    }
}
