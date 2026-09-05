package com.datagent.agentv2.artifact.application;

import com.datagent.agentv2.artifact.domain.ArtifactStatus;
import com.datagent.agentv2.artifact.domain.ArtifactType;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactObjectEntity;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.artifact.ArtifactObjectService;
import com.datagent.agentv2.persistence.service.artifact.ConversationMessageArtifactService;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 批量组装消息附件，并在下载前校验租户拦截后的会话归属。 */
@Service
public final class ArtifactQueryService {

    private final ConversationMessageArtifactService relations;
    private final ArtifactObjectService objects;
    private final MessageUiBlockService blocks;
    private final ConversationMessageService messages;
    private final ArtifactJsonDocumentCodec json;
    private final ArtifactStorage storage;

    public ArtifactQueryService(
            ConversationMessageArtifactService relations,
            ArtifactObjectService objects,
            MessageUiBlockService blocks,
            ConversationMessageService messages,
            ArtifactJsonDocumentCodec json,
            ArtifactStorage storage) {
        this.relations = relations;
        this.objects = objects;
        this.blocks = blocks;
        this.messages = messages;
        this.json = json;
        this.storage = storage;
    }

    public Map<String, MessageArtifactBundle> findByMessageIds(
            String sessionId, Collection<String> messageIds) {
        List<String> ids = List.copyOf(messageIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ConversationMessageArtifactEntity> relationRows = relations.listByMessageIds(ids);
        List<String> artifactIds =
                relationRows.stream()
                        .map(ConversationMessageArtifactEntity::getArtifactId)
                        .distinct()
                        .toList();
        Map<String, ArtifactObjectEntity> byArtifact =
                objects.listByArtifactIds(artifactIds).stream()
                        .collect(
                                Collectors.toMap(
                                        ArtifactObjectEntity::getArtifactId, Function.identity()));
        Map<String, List<ArtifactProjection>> artifactsByMessage = new HashMap<>();
        relationRows.forEach(
                relation -> {
                    ArtifactObjectEntity artifact = byArtifact.get(relation.getArtifactId());
                    if (artifact != null
                            && ArtifactStatus.READY.name().equals(artifact.getStatus())) {
                        artifactsByMessage
                                .computeIfAbsent(
                                        relation.getConversationMessageId(),
                                        ignored -> new java.util.ArrayList<>())
                                .add(toProjection(sessionId, artifact));
                    }
                });
        Map<String, List<MessageUiBlockProjection>> blocksByMessage =
                blocks.listByMessageIds(ids).stream()
                        .map(
                                block ->
                                        Map.entry(
                                                block.getConversationMessageId(),
                                                new MessageUiBlockProjection(
                                                        block.getUiBlockId(),
                                                        block.getType(),
                                                        block.getSchemaVersion(),
                                                        block.getVariant(),
                                                        json.read(block.getPayload()),
                                                        block.getDisplayOrder())))
                        .collect(
                                Collectors.groupingBy(
                                        Map.Entry::getKey,
                                        LinkedHashMap::new,
                                        Collectors.mapping(
                                                Map.Entry::getValue, Collectors.toList())));
        Map<String, MessageArtifactBundle> result = new LinkedHashMap<>();
        ids.forEach(
                id ->
                        result.put(
                                id,
                                new MessageArtifactBundle(
                                        artifactsByMessage.getOrDefault(id, List.of()),
                                        blocksByMessage.getOrDefault(id, List.of()))));
        return Map.copyOf(result);
    }

    public Optional<ArtifactDownload> findDownload(String sessionId, String artifactId) {
        ArtifactObjectEntity artifact = objects.findByArtifactId(artifactId);
        if (artifact == null || !ArtifactStatus.READY.name().equals(artifact.getStatus())) {
            return Optional.empty();
        }
        boolean belongs =
                relations.listByArtifactId(artifactId).stream()
                        .map(
                                row ->
                                        messages.findByConversationMessageId(
                                                row.getConversationMessageId()))
                        .filter(java.util.Objects::nonNull)
                        .map(ConversationMessage::getConversationSessionId)
                        .anyMatch(sessionId::equals);
        if (!belongs) {
            return Optional.empty();
        }
        return Optional.of(
                new ArtifactDownload(
                        artifact.getDisplayName(),
                        artifact.getMediaType(),
                        artifact.getSizeBytes(),
                        artifact.getSha256(),
                        artifact.getStorageKey(),
                        storage));
    }

    private static ArtifactProjection toProjection(
            String sessionId, ArtifactObjectEntity artifact) {
        return new ArtifactProjection(
                artifact.getArtifactId(),
                ArtifactType.valueOf(artifact.getArtifactType()),
                artifact.getDisplayName(),
                artifact.getMediaType(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getPageOrSheetCount(),
                "/api/v1/agent-sessions/"
                        + sessionId
                        + "/artifacts/"
                        + artifact.getArtifactId()
                        + "/content");
    }
}
