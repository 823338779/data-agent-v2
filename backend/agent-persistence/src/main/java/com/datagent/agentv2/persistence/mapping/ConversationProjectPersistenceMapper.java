package com.datagent.agentv2.persistence.mapping;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.persistence.entity.ConversationProjectEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ObjectFactory;
import org.mapstruct.ReportingPolicy;

/** 项目领域模型与数据库实体的纯转换，不执行查询。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ConversationProjectPersistenceMapper {
    ConversationProject toModel(ConversationProjectEntity entity);

    ConversationProjectEntity toEntity(ConversationProject model);

    @ObjectFactory
    default ConversationProjectEntity newEntity() {
        return ConversationProjectEntity.forUpdate();
    }
}
