package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.memory.review.MemoryReviewHistoryPort;
import com.datagent.agentv2.memory.review.MemoryReviewMessage;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 只从数据库完整消息构造证据；批量查询执行状态，工具过程和展示时间永不进入学习输入。 */
@Component
@RequiredArgsConstructor
public class WebMemoryReviewHistoryAdapter implements MemoryReviewHistoryPort {
    private final ConversationMessageService messages;
    private final AgentExecutionService executions;
    private final MemoryService memory;
    private final WebMemoryEvidenceMapper models;

    @Override
    public List<MemoryReviewMessage> read(MemoryReviewClaim claim, long afterSequence, int limit) {
        var rows =
                messages.findReviewPage(
                        claim.conversationId(), afterSequence, claim.throughSequence(), limit);
        var ids =
                rows.stream()
                        .map(ConversationMessage::getExecutionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        var statuses =
                executions.findByExecutionIds(ids).stream()
                        .collect(
                                Collectors.toMap(
                                        AgentExecution::getExecutionId, Function.identity()));
        var state = memory.snapshot().state();
        var result = new ArrayList<MemoryReviewMessage>();
        long cursor = afterSequence;
        for (var message : rows) {
            // 查询返回的序号空洞已证实不存在；每页只填有限个排除项，避免巨大的历史空洞分配无界内存。
            while (cursor + 1 < message.getConversationMessageSequence() && result.size() < limit) {
                result.add(
                        new MemoryReviewMessage(
                                ++cursor, MemoryReviewMessage.State.EXCLUDED, null));
            }
            if (result.size() >= limit) {
                return List.copyOf(result);
            }
            result.add(classify(message, statuses.get(message.getExecutionId()), state));
            cursor = message.getConversationMessageSequence();
        }
        if (rows.size() < limit) {
            while (cursor < claim.throughSequence() && result.size() < limit) {
                result.add(
                        new MemoryReviewMessage(
                                ++cursor, MemoryReviewMessage.State.EXCLUDED, null));
            }
        }
        return List.copyOf(result);
    }

    private MemoryReviewMessage classify(
            ConversationMessage row, AgentExecution execution, MemoryState state) {
        long sequence = row.getConversationMessageSequence();
        // 旧消息缺少可信数据库时间时永无新学习资格，即使其历史 execution 未终态也不能永久阻塞新代际。
        if (row.getMemoryEvidenceCreatedAt() == null
                || row.getMemoryEvidenceCreatedAt().isBefore(state.learningEnabledAt())) {
            return new MemoryReviewMessage(sequence, MemoryReviewMessage.State.EXCLUDED, null);
        }
        if (execution != null && execution.getStatus() == ExecutionStatus.RUNNING) {
            return new MemoryReviewMessage(sequence, MemoryReviewMessage.State.PENDING, null);
        }
        if (execution == null
                || execution.getStatus() != ExecutionStatus.DONE
                || row.getVersion() == null
                || row.getVersion() < 1
                || row.getContent() == null
                || row.getContent().isBlank()
                || (row.getRole() != MessageRole.USER && row.getRole() != MessageRole.ASSISTANT)
                || (row.getRole() == MessageRole.USER
                        && !OperatorContext.requiredUserId().equals(row.getAuthorUserId()))) {
            return new MemoryReviewMessage(sequence, MemoryReviewMessage.State.EXCLUDED, null);
        }
        return new MemoryReviewMessage(
                sequence, MemoryReviewMessage.State.ELIGIBLE, models.toEvidence(row));
    }
}
