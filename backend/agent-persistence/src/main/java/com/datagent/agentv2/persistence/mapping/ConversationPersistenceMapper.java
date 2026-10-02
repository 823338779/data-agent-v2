package com.datagent.agentv2.persistence.mapping;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionFailureCode;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.json.ConversationProcessDocumentCodec;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/** 会话执行域业务模型与持久化 Entity 之间的唯一映射边界。 */
@Mapper(
        componentModel = "spring",
        injectionStrategy = org.mapstruct.InjectionStrategy.CONSTRUCTOR,
        uses = ConversationProcessDocumentCodec.class,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ConversationPersistenceMapper {

    default MessageRole toMessageRole(String value) {
        return MessageRole.fromValue(value);
    }

    default String toMessageRoleValue(MessageRole role) {
        return role.value();
    }

    default ExecutionStatus toExecutionStatus(String value) {
        return ExecutionStatus.fromValue(value);
    }

    default String toExecutionStatusValue(ExecutionStatus status) {
        return status.value();
    }

    default ExecutionEventType toExecutionEventType(String value) {
        return ExecutionEventType.fromValue(value);
    }

    default String toExecutionEventTypeValue(ExecutionEventType type) {
        return type.value();
    }

    default ExecutionPhase toExecutionPhase(String value) {
        return ExecutionPhase.fromValue(value);
    }

    default String toExecutionPhaseValue(ExecutionPhase phase) {
        return phase.value();
    }

    default ExecutionFailureCode toExecutionFailureCode(String value) {
        return value == null ? null : ExecutionFailureCode.fromValue(value);
    }

    default String toExecutionFailureCodeValue(ExecutionFailureCode code) {
        return code == null ? null : code.value();
    }

    ConversationSession toModel(ConversationSessionEntity entity);

    ConversationSessionEntity toEntity(ConversationSession model);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "pinnedAt", ignore = true)
    void updateEntity(ConversationSession model, @MappingTarget ConversationSessionEntity entity);

    default void updateModel(ConversationSessionEntity entity, ConversationSession model) {
        model.acknowledgePersistence(
                entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    List<ConversationSession> toSessionModels(List<ConversationSessionEntity> entities);

    ConversationMessage toModel(ConversationMessageEntity entity);

    ConversationMessageEntity toEntity(ConversationMessage model);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    void updateEntity(ConversationMessage model, @MappingTarget ConversationMessageEntity entity);

    default void updateModel(ConversationMessageEntity entity, ConversationMessage model) {
        model.acknowledgePersistence(
                entity.getVersion(), entity.getCreatedAt(), entity.getMemoryEvidenceCreatedAt());
    }

    List<ConversationMessage> toMessageModels(List<ConversationMessageEntity> entities);

    AgentExecution toModel(AgentExecutionEntity entity);

    AgentExecutionEntity toEntity(AgentExecution model);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    void updateEntity(AgentExecution model, @MappingTarget AgentExecutionEntity entity);

    default void updateModel(AgentExecutionEntity entity, AgentExecution model) {
        model.acknowledgePersistence(entity.getVersion());
    }

    List<AgentExecution> toExecutionModels(List<AgentExecutionEntity> entities);

    AgentExecutionEvent toModel(AgentExecutionEventEntity entity);

    AgentExecutionEventEntity toEntity(AgentExecutionEvent model);

    List<AgentExecutionEvent> toEventModels(List<AgentExecutionEventEntity> entities);

    AgentExecutionStreamCheckpoint toModel(AgentExecutionStreamCheckpointEntity entity);

    AgentExecutionStreamCheckpointEntity toEntity(AgentExecutionStreamCheckpoint model);

    List<AgentExecutionStreamCheckpoint> toStreamModels(
            List<AgentExecutionStreamCheckpointEntity> entities);
}
