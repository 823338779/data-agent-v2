package com.datagent.agentv2.conversation.context;

import java.util.List;

/** 上下文策略向中立运行时组合器提供的纯 token 估算边界，不读取或写入策略状态。 */
public interface ContextInputEstimator {

    String strategyId();

    /** 估算给定消息及其角色、消息边界和工具协议开销。 */
    int estimateMessages(List<ContextMessage> messages);

    /** 估算固定 system、工具定义、协议固定开销与给定动态消息组成的完整输入。 */
    int estimateCompleteInput(ConversationContextRequest request, List<ContextMessage> messages);
}
