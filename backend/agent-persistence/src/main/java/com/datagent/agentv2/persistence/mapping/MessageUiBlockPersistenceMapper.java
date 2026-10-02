package com.datagent.agentv2.persistence.mapping;

import com.datagent.agentv2.conversation.domain.MessageUiBlock;
import com.datagent.agentv2.persistence.entity.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.json.MessageUiBlockJsonCodec;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 消息卡片领域对象与持久化实体之间的纯转换边界。 */
@Mapper(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        uses = MessageUiBlockJsonCodec.class,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MessageUiBlockPersistenceMapper {

    @Mapping(target = "payload", source = "payload", qualifiedByName = "readUiBlockPayload")
    MessageUiBlock toModel(MessageUiBlockEntity entity);
}
