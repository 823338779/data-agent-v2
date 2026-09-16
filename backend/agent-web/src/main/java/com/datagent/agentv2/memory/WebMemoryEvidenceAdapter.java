package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 真实用户消息证据边界；执行模式和冻结范围由服务端提供，模型只能引用允许的消息 ID。 */
@Component
@RequiredArgsConstructor
public class WebMemoryEvidenceAdapter implements MemoryEvidencePort {
    private final ConversationMessageService messages;
    private final WebMemoryEvidenceMapper models;

    /** 请求工厂在模型调用前冻结可引用 ID；提交时仍须再次 resolve，不信任旧正文副本。 */
    @Transactional(readOnly = true)
    public List<MemoryEvidence> currentUserEvidence(String executionId) {
        if (executionId == null || executionId.isBlank()) return List.of();
        return messages.findByExecutionId(executionId).stream()
                .filter(WebMemoryEvidenceAdapter::validUser)
                .filter(message -> executionId.equals(message.getExecutionId()))
                .map(models::toEvidence)
                .toList();
    }

    /**
     * 加入统一记忆写入事务；只做领域读取，不反向锁 execution/assistant，避免与最终回答提交形成死锁。 缺失、删除或越界证据返回不足集合，由写入 policy
     * 持久化明确拒绝，不能在内部事务代理抛业务异常标记回滚。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MemoryEvidence> resolve(MemoryWriteContext context, List<String> ids) {
        if (ids == null || ids.isEmpty() || context.caller() == MemoryWriteContext.Caller.UI)
            return List.of();
        return messages.findEvidenceByIds(ids).stream()
                .filter(WebMemoryEvidenceAdapter::validUser)
                .filter(
                        message ->
                                Objects.equals(
                                        context.conversationId(),
                                        message.getConversationSessionId()))
                .filter(message -> inBoundary(context, message))
                .map(models::toEvidence)
                .toList();
    }

    private static boolean inBoundary(MemoryWriteContext context, ConversationMessage message) {
        if (context.caller() == MemoryWriteContext.Caller.MAIN)
            return Objects.equals(context.executionId(), message.getExecutionId());
        var claim = context.reviewClaim();
        return Objects.equals(claim.conversationId(), message.getConversationSessionId())
                && message.getConversationMessageSequence() > claim.fromSequence()
                && message.getConversationMessageSequence() <= claim.throughSequence();
    }

    private static boolean validUser(ConversationMessage message) {
        return message.getRole() == MessageRole.USER
                && OperatorContext.requiredUserId().equals(message.getAuthorUserId())
                && message.getExecutionId() != null
                && !message.getExecutionId().isBlank()
                && message.getVersion() != null
                && message.getVersion() >= 1
                && message.getConversationMessageSequence() != null
                // 历史消息没有可信数据库时间，不能回退到存在主机偏差的展示审计时间来授予恢复权限。
                && message.getMemoryEvidenceCreatedAt() != null
                && message.getContent() != null
                && !message.getContent().isBlank();
    }
}
