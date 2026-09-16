package com.datagent.agentv2.git;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitFinalAnswerComposerTest {
    @Test
    void onlyRegisteredGitBlocksAreAddedWithoutChangingOtherAnswerContent() {
        var blocks = mock(MessageUiBlockService.class);
        var artifact = new MessageUiBlockEntity();
        artifact.setType("artifact-card");
        artifact.setSchemaVersion(1);
        artifact.setUiBlockId("artifact");
        var git = new MessageUiBlockEntity();
        git.setType("git-change-card");
        git.setSchemaVersion(1);
        git.setUiBlockId("registered");
        var message = ConversationMessage.assistantPlaceholder("m1", "s1", "e1", 2);
        var composer = new GitFinalAnswerComposer(blocks);
        when(blocks.listByMessageIds(List.of("m1"))).thenReturn(List.of(artifact));
        assertThat(composer.appendRegisteredCards(message, "普通终答")).isEqualTo("普通终答");
        when(blocks.listByMessageIds(List.of("m1"))).thenReturn(List.of(artifact, git));
        String answer = "普通终答\n\n<agent-ui block-id=\"registered\"></agent-ui>";
        assertThat(composer.appendRegisteredCards(message, "普通终答")).isEqualTo(answer);
        assertThat(composer.appendRegisteredCards(message, answer)).isEqualTo(answer);
    }
}
