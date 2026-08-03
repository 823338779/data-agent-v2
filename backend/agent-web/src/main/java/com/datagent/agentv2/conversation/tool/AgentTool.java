package com.datagent.agentv2.conversation.tool;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Agent 可注册的领域工具契约。 */
public interface AgentTool {

    /** 返回模型调用和注册表使用的稳定工具名称。 */
    String name();

    /** 返回提供给模型理解工具用途的说明。 */
    String description();

    /** 返回限制工具参数结构的 JSON Schema。 */
    JsonNode parametersSchema();

    /** 返回单次工具执行允许的最长时间。 */
    default Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /** 标记工具是否支持使用稳定幂等键安全重试。 */
    default boolean supportsIdempotency() {
        return false;
    }

    /** 使用模型参数和服务端执行上下文调用工具。 */
    JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception;

    /**
     * 工具调用所需但不得序列化给模型的服务端上下文。
     *
     * @param tenantId 当前租户标识
     * @param executionId 当前 Agent 执行标识
     * @param modelInvocationSequence 产生本次调用的模型调用序号
     * @param toolCallId provider 生成的工具调用标识
     * @param idempotencyKey 支持重试的工具使用的稳定幂等键
     */
    record InvocationContext(
            String tenantId,
            String executionId,
            long modelInvocationSequence,
            String toolCallId,
            String idempotencyKey) {}
}
