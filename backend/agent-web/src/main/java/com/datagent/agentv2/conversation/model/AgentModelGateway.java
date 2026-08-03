package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import java.util.List;
import java.util.function.Consumer;

/** Agent 领域访问大模型的流式调用端口，隔离具体模型供应商协议。 */
public interface AgentModelGateway {

    /**
     * 发起一次完整模型调用，并在供应商返回正文片段时同步回调回答增量。
     *
     * @param messages 本次调用可见的有序上下文消息
     * @param tools 可供模型选择的工具定义
     * @param answerDeltaConsumer 按供应商顺序接收回答增量的回调
     * @return 供应商确认完成后的最终回答或完整工具调用集合
     */
    ModelInvocationResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<String> answerDeltaConsumer);
}
