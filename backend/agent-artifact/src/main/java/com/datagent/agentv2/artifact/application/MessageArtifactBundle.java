package com.datagent.agentv2.artifact.application;

import java.util.List;

/** 一条消息的 Artifact 和 UI Block 有序集合。 */
public record MessageArtifactBundle(
        List<ArtifactProjection> artifacts, List<MessageUiBlockProjection> uiBlocks) {

    public MessageArtifactBundle {
        artifacts = List.copyOf(artifacts);
        uiBlocks = List.copyOf(uiBlocks);
    }

    public static MessageArtifactBundle empty() {
        return new MessageArtifactBundle(List.of(), List.of());
    }
}
