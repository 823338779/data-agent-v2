package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 已验证真实用户消息的纯转换，不查询数据库，不接收模型伪造的正文或作者。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WebMemoryEvidenceMapper {
    @Mapping(target = "createdAt", source = "memoryEvidenceCreatedAt")
    @Mapping(target = "evidenceId", source = "conversationMessageId")
    @Mapping(target = "kind", constant = "USER_MESSAGE")
    @Mapping(target = "conversationId", source = "conversationSessionId")
    @Mapping(target = "sequence", source = "conversationMessageSequence")
    @Mapping(target = "messageVersion", source = "version")
    MemoryEvidence toEvidence(ConversationMessage message);
}
