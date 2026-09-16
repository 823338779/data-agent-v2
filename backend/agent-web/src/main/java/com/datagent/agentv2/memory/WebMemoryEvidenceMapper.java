package com.datagent.agentv2.memory;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 已验证真实消息的纯转换；助手只能成为辅助语境，写入证据端口仍在调用前严格限定 USER。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WebMemoryEvidenceMapper {
    @Mapping(target = "createdAt", source = "memoryEvidenceCreatedAt")
    @Mapping(target = "evidenceId", source = "conversationMessageId")
    @Mapping(
            target = "kind",
            expression =
                    "java(message.getRole() == com.datagent.agentv2.conversation.domain.MessageRole.USER ? com.datagent.agentv2.memory.MemoryEvidence.Kind.USER_MESSAGE : com.datagent.agentv2.memory.MemoryEvidence.Kind.ASSISTANT_CONTEXT)")
    @Mapping(target = "conversationId", source = "conversationSessionId")
    @Mapping(target = "sequence", source = "conversationMessageSequence")
    @Mapping(target = "messageVersion", source = "version")
    MemoryEvidence toEvidence(ConversationMessage message);
}
