package com.datagent.agentv2.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.event.ConversationMessageChanged;
import org.junit.jupiter.api.Test;

class ArtifactDomainContractTest {

    @Test
    void artifactTypesExposeExactOfficeMediaTypeAndExtension() {
        assertThat(ArtifactType.DOCX.mediaType())
                .isEqualTo(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(ArtifactType.DOCX.extension()).isEqualTo("docx");
        assertThat(ArtifactType.PPTX.mediaType())
                .isEqualTo(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(ArtifactType.PPTX.extension()).isEqualTo("pptx");
        assertThat(ArtifactType.XLSX.mediaType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(ArtifactType.XLSX.extension()).isEqualTo("xlsx");
    }

    @Test
    void onlyCompletedBuildStatesAreTerminal() {
        assertThat(ArtifactBuildStatus.PREPARED.isTerminal()).isFalse();
        assertThat(ArtifactBuildStatus.RUNNING.isTerminal()).isFalse();
        assertThat(ArtifactBuildStatus.RENDERING.isTerminal()).isFalse();
        assertThat(ArtifactBuildStatus.VERIFYING.isTerminal()).isFalse();
        assertThat(ArtifactBuildStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ArtifactBuildStatus.FAILED.isTerminal()).isTrue();
        assertThat(ArtifactBuildStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(ArtifactBuildStatus.TIMED_OUT.isTerminal()).isTrue();
    }

    @Test
    void messageChangeEventLivesInDomainAndPreservesVersion() {
        ConversationMessageChanged changed =
                new ConversationMessageChanged("tenant", "session", "message", 7L);

        assertThat(changed.tenantId()).isEqualTo("tenant");
        assertThat(changed.conversationSessionId()).isEqualTo("session");
        assertThat(changed.conversationMessageId()).isEqualTo("message");
        assertThat(changed.messageVersion()).isEqualTo(7L);
    }
}
