package com.datagent.agentv2.artifact.domain;

import java.util.Objects;

/** 一条 assistant 消息与一个正式产物之间的有序关系。 */
public record MessageArtifact(
        String messageArtifactId,
        String conversationMessageId,
        String artifactId,
        int displayOrder) {

    public MessageArtifact {
        Objects.requireNonNull(messageArtifactId, "messageArtifactId");
        Objects.requireNonNull(conversationMessageId, "conversationMessageId");
        Objects.requireNonNull(artifactId, "artifactId");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must not be negative");
        }
    }
}
