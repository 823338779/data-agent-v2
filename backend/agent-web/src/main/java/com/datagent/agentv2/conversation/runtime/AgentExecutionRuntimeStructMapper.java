package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.cluster.AgentExecutionCancellationSignal;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 不同运行时协议中的执行认领结构映射。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AgentExecutionRuntimeStructMapper {

    AgentExecutionRuntimeStructMapper INSTANCE =
            Mappers.getMapper(AgentExecutionRuntimeStructMapper.class);

    @Mapping(target = "executionAttemptNumber", source = "executionAttempt")
    AgentExecutionPort.ClaimedAgentExecution toClaimedExecution(ContextExecutionRef execution);

    AgentExecutionPort.ClaimedAgentExecution toClaimedExecution(
            AgentExecutionCancellationSignal signal);
}
