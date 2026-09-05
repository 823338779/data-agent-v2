package com.datagent.agentv2.persistence.mapping;

import com.datagent.agentv2.artifact.domain.ArtifactBuildJob;
import com.datagent.agentv2.artifact.domain.ArtifactObject;
import com.datagent.agentv2.artifact.domain.MessageArtifact;
import com.datagent.agentv2.artifact.domain.MessageUiBlock;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactBuildJobEntity;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactObjectEntity;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.json.ArtifactJsonDocumentCodec;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Artifact 领域对象与持久化实体之间的纯转换边界。 */
@Mapper(
        componentModel = "spring",
        uses = ArtifactJsonDocumentCodec.class,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ArtifactPersistenceMapper {

    @Mapping(target = "buildJobId", source = "artifactBuildJobId")
    ArtifactBuildJob toModel(ArtifactBuildJobEntity entity);

    @Mapping(target = "artifactBuildJobId", source = "buildJobId")
    ArtifactBuildJobEntity toEntity(ArtifactBuildJob model);

    @Mapping(target = "buildJobId", source = "artifactBuildJobId")
    ArtifactObject toModel(ArtifactObjectEntity entity);

    @Mapping(target = "artifactBuildJobId", source = "buildJobId")
    ArtifactObjectEntity toEntity(ArtifactObject model);

    @Mapping(target = "messageArtifactId", source = "conversationMessageArtifactId")
    MessageArtifact toModel(ConversationMessageArtifactEntity entity);

    @Mapping(target = "conversationMessageArtifactId", source = "messageArtifactId")
    ConversationMessageArtifactEntity toEntity(MessageArtifact model);

    MessageUiBlock toModel(MessageUiBlockEntity entity);

    MessageUiBlockEntity toEntity(MessageUiBlock model);
}
