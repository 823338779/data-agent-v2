package com.datagent.agentv2.conversation.cluster;

import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 将内部回答增量映射为跨实例通知。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AgentExecutionDeltaNotificationMapper {

    @Mapping(target = "delta", source = "answerDelta")
    AgentExecutionDeltaNotification toNotification(
            ExecutionStreamCheckpointCoordinator.AnswerDelta published);
}
