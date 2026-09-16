package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 最终回答事务中的可靠审查标记；只持久化边界，不发布内存任务，也不调用模型。 */
@Component
@RequiredArgsConstructor
public class MemoryReviewMarker {
    private final MemoryStateService states;
    private final AgentExecutionService executions;
    private final ConversationMessageService messages;
    private final MemoryReviewJobService jobs;
    private final MemoryProperties properties;
    private final IdGenerator ids;

    /**
     * 与 DONE 同成败；先持 state 锁再访问 job，不反向锁 execution 或消息。 重新读取数据库生成的证据时间，避免刚 save 的内存对象缺少默认值或主机审计时间漂移。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(String executionId) {
        var state = states.lockCurrent();
        if (!state.autoLearningEnabled()) return;
        var execution = executions.findByExecutionId(executionId);
        if (execution == null || execution.getStatus() != ExecutionStatus.DONE) return;
        var user =
                messages.findByConversationMessageId(
                        execution.getTriggeringConversationMessageId());
        if (user == null
                || user.getRole() != MessageRole.USER
                || !OperatorContext.requiredUserId().equals(user.getAuthorUserId())
                || !Objects.equals(executionId, user.getExecutionId())
                || !Objects.equals(
                        execution.getConversationSessionId(), user.getConversationSessionId())
                || user.getConversationMessageSequence() == null
                || user.getConversationMessageSequence() < 1
                || user.getMemoryEvidenceCreatedAt() == null
                || state.learningEnabledAt() == null
                || user.getMemoryEvidenceCreatedAt().isBefore(state.learningEnabledAt())) return;
        long through =
                messages.findByExecutionId(executionId).stream()
                        .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                        .filter(
                                message ->
                                        Objects.equals(
                                                execution.getConversationSessionId(),
                                                message.getConversationSessionId()))
                        .filter(message -> message.getConversationMessageSequence() != null)
                        .mapToLong(message -> message.getConversationMessageSequence())
                        .max()
                        .orElse(user.getConversationMessageSequence());
        jobs.markCompleted(
                ids.next("memory_review"),
                execution.getConversationSessionId(),
                state.learningEpoch(),
                user.getConversationMessageSequence() - 1,
                Math.max(through, user.getConversationMessageSequence()),
                states.currentBusinessTime().plus(properties.getReviewDelay()));
    }
}
