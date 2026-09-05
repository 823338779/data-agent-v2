package com.datagent.agentv2.artifact.application;

import com.datagent.agentv2.artifact.domain.ArtifactStatus;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.artifact.storage.StoredArtifact;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.event.ConversationMessageChanged;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactObjectEntity;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.artifact.ArtifactObjectService;
import com.datagent.agentv2.persistence.service.artifact.ConversationMessageArtifactService;
import com.datagent.agentv2.persistence.service.artifact.MessageUiBlockService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 将已验证的 staging 文件原子发布，并在同一数据库事务中关联 assistant 消息。 */
@Service
@Slf4j
public final class ArtifactPublicationService {

    private final ArtifactStorage storage;
    private final ArtifactObjectService objects;
    private final ConversationMessageArtifactService relations;
    private final MessageUiBlockService blocks;
    private final ConversationMessageService messages;
    private final ArtifactJsonDocumentCodec json;
    private final IdGenerator ids;
    private final ApplicationEventPublisher events;

    public ArtifactPublicationService(
            ArtifactStorage storage,
            ArtifactObjectService objects,
            ConversationMessageArtifactService relations,
            MessageUiBlockService blocks,
            ConversationMessageService messages,
            ArtifactJsonDocumentCodec json,
            IdGenerator ids,
            ApplicationEventPublisher events) {
        this.storage = storage;
        this.objects = objects;
        this.relations = relations;
        this.blocks = blocks;
        this.messages = messages;
        this.json = json;
        this.ids = ids;
        this.events = events;
    }

    @Transactional
    public ArtifactPublicationResult publish(ArtifactPublicationRequest request) throws IOException {
        String artifactId = ids.next("artifact");
        String uiBlockId = ids.next("ui_block");
        StoredArtifact stored = storage.publish(artifactId, request.stagedArtifact());
        AtomicBoolean cleaned = new AtomicBoolean();
        registerRollbackCleanup(stored, cleaned);
        try {
            ConversationMessage message = messages.lockAssistantByExecutionId(request.executionId());
            if (message == null) {
                throw new IllegalStateException("assistant message is unavailable for artifact publication");
            }
            ArtifactObjectEntity artifact = new ArtifactObjectEntity();
            artifact.setArtifactId(artifactId);
            artifact.setConversationSessionId(message.getConversationSessionId());
            artifact.setArtifactBuildJobId(request.buildJobId());
            artifact.setSpecificationVersion(request.specificationVersion());
            artifact.setArtifactType(request.artifactType().name());
            artifact.setStatus(ArtifactStatus.READY.name());
            artifact.setDisplayName(safeDisplayName(request.displayName(), request.artifactType().extension()));
            artifact.setMediaType(request.artifactType().mediaType());
            artifact.setStorageKey(stored.storageKey());
            artifact.setSizeBytes(stored.sizeBytes());
            artifact.setSha256(stored.sha256());
            artifact.setPageOrSheetCount(request.pageOrSheetCount());
            requireSaved(objects.save(artifact));

            ConversationMessageArtifactEntity relation = new ConversationMessageArtifactEntity();
            relation.setConversationMessageArtifactId(ids.next("message_artifact"));
            relation.setConversationMessageId(message.getConversationMessageId());
            relation.setArtifactId(artifactId);
            relation.setDisplayOrder(0);
            requireSaved(relations.save(relation));

            MessageUiBlockEntity block = new MessageUiBlockEntity();
            block.setUiBlockId(uiBlockId);
            block.setConversationMessageId(message.getConversationMessageId());
            block.setType("artifact-card");
            block.setSchemaVersion(1);
            block.setVariant("default");
            block.setPayload(json.write(Map.of("artifactId", artifactId)));
            block.setDisplayOrder(0);
            requireSaved(blocks.save(block));

            String marker = "<agent-ui block-id=\"" + uiBlockId + "\"></agent-ui>";
            message.completeAssistantContent(
                    message.getContent().isBlank() ? marker : message.getContent() + "\n\n" + marker);
            requireSaved(messages.updateById(message));
            events.publishEvent(
                    new ConversationMessageChanged(
                            TenantContext.requiredTenantId(),
                            message.getConversationSessionId(),
                            message.getConversationMessageId(),
                            Objects.requireNonNullElse(message.getVersion(), 0L)));
            return new ArtifactPublicationResult(artifactId, uiBlockId);
        } catch (RuntimeException failure) {
            cleanupAfterFailure(stored, cleaned, failure);
            throw failure;
        }
    }

    /**
     * 文件移动发生在数据库提交之前，因此必须覆盖提交阶段失败，而不仅是方法体内异常；回调只持有服务端返回的精确正式文件句柄。
     */
    private void registerRollbackCleanup(StoredArtifact stored, AtomicBoolean cleaned) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED && cleaned.compareAndSet(false, true)) {
                            try {
                                storage.deletePublished(stored);
                            } catch (IOException failure) {
                                log.error("Artifact rollback cleanup failed: code=FINAL_FILE_DELETE_FAILED", failure);
                            }
                        }
                    }
                });
    }

    private void cleanupAfterFailure(
            StoredArtifact stored, AtomicBoolean cleaned, RuntimeException originalFailure) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        try {
            storage.deletePublished(stored);
        } catch (IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void requireSaved(boolean saved) {
        if (!saved) {
            throw new IllegalStateException("artifact publication persistence failed");
        }
    }

    private static String safeDisplayName(String displayName, String extension) {
        String name = Objects.requireNonNull(displayName, "displayName").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || !name.toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)) {
            throw new IllegalArgumentException("artifact display name has an invalid extension");
        }
        return name;
    }
}
