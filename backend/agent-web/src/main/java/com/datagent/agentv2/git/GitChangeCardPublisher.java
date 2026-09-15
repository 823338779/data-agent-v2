package com.datagent.agentv2.git;

import com.datagent.agentv2.conversation.domain.event.ConversationMessageChanged;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Web 的候选发布适配器；冻结文件在此前完成，本事务只登记候选、消息和 UI Block。 */
@Service
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GitChangeCardPublisher
        implements com.datagent.agentv2.git.port.GitChangeCardPublisher {
    private final GitSessionResolver sessions;
    private final ConversationMessageService messages;
    private final GitChangePersistenceService changes;
    private final MessageUiBlockService blocks;
    private final ArtifactJsonDocumentCodec json;
    private final IdGenerator ids;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public void publish(String executionId, GitChangeEntity candidate) {
        // 锁 assistant 后再校验归属和登记，防止正文并发追加覆盖卡片；发布失败须回滚活动候选指针。
        var message = messages.lockAssistantByExecutionId(executionId);
        String session = sessions.resolveSessionId(executionId);
        if (message == null
                || !session.equals(candidate.getConversationSessionId())
                || !session.equals(message.getConversationSessionId()))
            throw new GitException("GIT_PUBLICATION_FAILED", "候选与消息不属于同一执行会话");
        candidate.setConversationMessageId(message.getConversationMessageId());
        changes.register(candidate);
        String blockId = ids.next("ui_block");
        var block = new MessageUiBlockEntity();
        block.setUiBlockId(blockId);
        block.setConversationMessageId(message.getConversationMessageId());
        block.setType("git-change-card");
        block.setSchemaVersion(1);
        block.setVariant("default");
        block.setPayload(
                json.write(Map.of("changeId", candidate.getChangeId(), "sessionId", session)));
        block.setDisplayOrder(
                blocks
                                .listByMessageIds(
                                        java.util.List.of(message.getConversationMessageId()))
                                .stream()
                                .mapToInt(b -> b.getDisplayOrder())
                                .max()
                                .orElse(-1)
                        + 1);
        requireSaved(blocks.save(block));
        String marker = "<agent-ui block-id=\"" + blockId + "\"></agent-ui>";
        message.completeAssistantContent(
                message.getContent().isBlank() ? marker : message.getContent() + "\n\n" + marker);
        requireSaved(messages.updateById(message));
        events.publishEvent(
                new ConversationMessageChanged(
                        TenantContext.requiredTenantId(),
                        session,
                        message.getConversationMessageId(),
                        Objects.requireNonNullElse(message.getVersion(), 0L)));
    }

    private static void requireSaved(boolean saved) {
        if (!saved) throw new GitException("GIT_PUBLICATION_FAILED", "候选卡片登记失败");
    }
}
