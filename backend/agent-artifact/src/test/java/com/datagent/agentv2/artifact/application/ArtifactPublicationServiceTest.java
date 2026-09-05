package com.datagent.agentv2.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.artifact.config.ArtifactProperties;
import com.datagent.agentv2.artifact.domain.ArtifactType;
import com.datagent.agentv2.artifact.storage.LocalArtifactStorage;
import com.datagent.agentv2.artifact.storage.StagedArtifact;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.artifact.ArtifactObjectService;
import com.datagent.agentv2.persistence.service.artifact.ConversationMessageArtifactService;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class ArtifactPublicationServiceTest {

    @TempDir Path root;

    @Test
    void publishesMetadataBlockAndMessageChange() throws Exception {
        ArtifactProperties properties = new ArtifactProperties();
        properties.setRootDirectory(root);
        LocalArtifactStorage storage = new LocalArtifactStorage(properties);
        StagedArtifact staged = storage.createStaging("job-1", ArtifactType.DOCX);
        storage.write(staged, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        ArtifactObjectService objects = mock(ArtifactObjectService.class);
        ConversationMessageArtifactService relations = mock(ConversationMessageArtifactService.class);
        MessageUiBlockService blocks = mock(MessageUiBlockService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ConversationMessage message =
                ConversationMessage.assistantPlaceholder("message-1", "session-1", "execution-1", 2);
        when(messages.lockAssistantByExecutionId("execution-1")).thenReturn(message);
        when(messages.updateById(message)).thenReturn(true);
        when(objects.save(any())).thenReturn(true);
        when(relations.save(any())).thenReturn(true);
        when(blocks.save(any())).thenReturn(true);
        IdGenerator ids = prefix -> prefix + "-1";
        ArtifactPublicationService service =
                new ArtifactPublicationService(
                        storage,
                        objects,
                        relations,
                        blocks,
                        messages,
                        new ArtifactJsonDocumentCodec(),
                        ids,
                        events);

        ArtifactPublicationResult result =
                service.publish(
                        new ArtifactPublicationRequest(
                                "job-1",
                                "execution-1",
                                ArtifactType.DOCX,
                                "report.docx",
                                JsonMapper.builder().build().createObjectNode().put("title", "Report"),
                                1,
                                1,
                                staged));

        assertThat(result.artifactId()).isEqualTo("artifact-1");
        assertThat(message.getContent()).contains("<agent-ui block-id=\"ui_block-1\"></agent-ui>");
        verify(objects).save(any());
        verify(relations).save(any());
        verify(blocks).save(any());
        verify(events).publishEvent(any(Object.class));
    }
}
