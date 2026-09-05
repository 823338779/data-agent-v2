package com.datagent.agentv2.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.persistence.entity.artifact.ArtifactObjectEntity;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.artifact.ArtifactObjectService;
import com.datagent.agentv2.persistence.service.artifact.ConversationMessageArtifactService;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactQueryServiceTest {

    @Test
    void assemblesMessageArtifactsWithThreeBulkQueries() {
        ConversationMessageArtifactService relations =
                mock(ConversationMessageArtifactService.class);
        ArtifactObjectService objects = mock(ArtifactObjectService.class);
        MessageUiBlockService blocks = mock(MessageUiBlockService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        ConversationMessageArtifactEntity relation = new ConversationMessageArtifactEntity();
        relation.setConversationMessageId("message-1");
        relation.setArtifactId("artifact-1");
        relation.setDisplayOrder(0);
        ArtifactObjectEntity object = new ArtifactObjectEntity();
        object.setArtifactId("artifact-1");
        object.setArtifactType("DOCX");
        object.setDisplayName("report.docx");
        object.setMediaType("application/docx");
        object.setSizeBytes(12L);
        object.setSha256("a".repeat(64));
        object.setStatus("READY");
        MessageUiBlockEntity block = new MessageUiBlockEntity();
        block.setUiBlockId("block-1");
        block.setConversationMessageId("message-1");
        block.setType("artifact-card");
        block.setSchemaVersion(1);
        block.setVariant("default");
        block.setPayload("{\"artifactId\":\"artifact-1\"}");
        block.setDisplayOrder(0);
        when(relations.listByMessageIds(List.of("message-1", "message-2")))
                .thenReturn(List.of(relation));
        when(objects.listByArtifactIds(List.of("artifact-1"))).thenReturn(List.of(object));
        when(blocks.listByMessageIds(List.of("message-1", "message-2"))).thenReturn(List.of(block));
        ArtifactQueryService service =
                new ArtifactQueryService(
                        relations,
                        objects,
                        blocks,
                        messages,
                        new ArtifactJsonDocumentCodec(),
                        mock(com.datagent.agentv2.artifact.storage.ArtifactStorage.class));

        Map<String, MessageArtifactBundle> result =
                service.findByMessageIds("session-1", List.of("message-1", "message-2"));

        assertThat(result.get("message-1").artifacts().getFirst().downloadUrl())
                .isEqualTo("/api/v1/agent-sessions/session-1/artifacts/artifact-1/content");
        assertThat(result.get("message-1").uiBlocks()).hasSize(1);
        assertThat(result.get("message-2")).isEqualTo(MessageArtifactBundle.empty());
        verify(relations).listByMessageIds(List.of("message-1", "message-2"));
        verify(objects).listByArtifactIds(List.of("artifact-1"));
        verify(blocks).listByMessageIds(List.of("message-1", "message-2"));
    }
}
