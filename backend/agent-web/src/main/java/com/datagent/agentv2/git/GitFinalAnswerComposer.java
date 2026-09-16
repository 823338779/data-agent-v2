package com.datagent.agentv2.git;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 终答只补回当前消息已登记的 Git 卡片；不从模型文本推断卡片授权，不迁移 artifact 投影。 */
@Service
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public final class GitFinalAnswerComposer {
    private final MessageUiBlockService blocks;

    public String appendRegisteredCards(ConversationMessage message, String answer) {
        String content = answer;
        for (var block : blocks.listByMessageIds(List.of(message.getConversationMessageId()))) {
            if (!"git-change-card".equals(block.getType())
                    || !Integer.valueOf(1).equals(block.getSchemaVersion())) continue;
            String marker = "<agent-ui block-id=\"" + block.getUiBlockId() + "\"></agent-ui>";
            if (!content.contains(marker))
                content = content.isBlank() ? marker : content + "\n\n" + marker;
        }
        return content;
    }
}
