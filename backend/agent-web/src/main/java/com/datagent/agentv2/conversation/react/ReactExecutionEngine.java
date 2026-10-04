package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.context.ContextModelMessageMapper;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextRequestFactory;
import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.exception.ReactLimitExceededException;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
import com.datagent.agentv2.conversation.tool.PreparedToolInvocation;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.memory.tool.MemoryInvocationRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 在模型调用与工具执行边界之间驱动可恢复的 ReAct 循环。 */
@Service
@RequiredArgsConstructor
public final class ReactExecutionEngine {

    /** 模型调用次数超过单次执行上限时使用的稳定错误信息。 */
    private static final String ERROR_MODEL_INVOCATION_LIMIT =
            "ReAct model invocation limit exceeded";

    /** 工具调用数量超过单次执行上限时使用的稳定错误信息。 */
    private static final String ERROR_TOOL_CALL_LIMIT = "ReAct tool call limit exceeded";

    /** 负责供应商模型调用并回调回答增量。 */
    private final AgentModelGateway model;

    /** 按服务配置调用唯一上下文策略。 */
    private final ConversationContextStrategyResolver contexts;

    /** 将 invocation 边界、模型预算和当轮工具集冻结为策略请求。 */
    private final ConversationContextRequestFactory contextRequests;

    /** 在中立上下文消息和现有模型协议消息之间执行无损映射。 */
    private final ContextModelMessageMapper contextMessages;

    /** 建立、推进并完成模型调用的事务协调器。 */
    private final ModelInvocationCoordinator modelInvocationCoordinator;

    /** 执行模型已经归档的工具调用。 */
    private final ToolExecutionCoordinator tools;

    /** 提供本次 execution 当前可见的工具快照。 */
    private final ExecutionToolViews toolViews;

    /** 把可见 Tool 映射为模型协议定义。 */
    private final ToolModelDefinitionMapper toolDefinitions;

    /** 在循环推进前校验 owner、预算和取消状态。 */
    private final LocalExecutionGuard executionGuard;

    /** 约束单次 ReAct 执行允许的模型及工具调用数量。 */
    private final ReactLimitsProperties limits;

    /** 管理每次模型调用可引用的记忆 revision 与真实证据快照。 */
    private final MemoryInvocationRegistry memoryInvocations;

    private final MemoryMetrics metrics;

    /**
     * 从新的模型调用边界开始执行任务。
     *
     * @param run 当前进程正在运行的执行
     */
    public void execute(LocalAgentExecution run) {
        try {
            executeLoop(run);
        } finally {
            contexts.closeExecution(run.executionId());
        }
    }

    private void executeLoop(LocalAgentExecution run) {
        // 两个计数器覆盖本次 worker 生命周期内的循环推进，防止模型或工具无限递归。
        int modelInvocationCount = 0;
        int toolCallCount = 0;

        // 显式 ReAct 循环只在完整模型调用或完整工具结果边界之间推进。
        while (true) {
            // 每轮开始前确认当前实例仍拥有执行权，并优先响应用户取消。
            executionGuard.assertRunningAndWithinDeadline(run);

            // 在创建新的持久化模型调用前执行次数限制，越界时不产生半成品记录。
            if (++modelInvocationCount > limits.getMaxModelInvocations()) {
                throw new ReactLimitExceededException(ERROR_MODEL_INVOCATION_LIMIT);
            }

            ModelInvocationCoordinator.ModelInvocationBoundary modelInvocation =
                    modelInvocationCoordinator.beginModelInvocation(run);

            try {

                // 每轮固定同一份工具定义，并把持久化消息上界和模型预算交给选中策略。
                // skill_read 可在前一轮激活新 Tool，因此每轮都必须重新读取可见集合。
                List<ModelToolDefinition> modelTools =
                        toolDefinitions.toModelDefinitions(
                                toolViews.require(run.executionId()).visibleTools().stream()
                                        .map(binding -> binding.tool())
                                        .toList());
                ConversationContextRequest contextRequest =
                        contextRequests.create(run, modelInvocation, modelTools);
                PreparedConversationContext prepared = contexts.prepare(contextRequest);
                var statistics = prepared.runtimeStatistics();
                metrics.recordInjection(
                        statistics.injected() + statistics.omitted(),
                        statistics.injected(),
                        statistics.contributionTokens());
                List<ModelInputMessage> input =
                        contextMessages.toModelMessages(prepared.messages());

                // 调用前后都执行 fencing；流式片段在回调中同步形成 checkpoint 和通知。
                executionGuard.assertRunningAndWithinDeadline(run);
                ModelInvocationResult result;
                try {
                    result =
                            model.complete(
                                    input,
                                    modelTools,
                                    delta ->
                                            modelInvocationCoordinator.checkpoint(
                                                    run, modelInvocation, delta));
                } catch (ModelStreamInterruptedException failure) {
                    // provider 失败也必须把已展示的 thinking 节点收敛为失败终态。
                    modelInvocationCoordinator.failModelOutput(run, modelInvocation);
                    throw failure;
                }
                executionGuard.assertRunningAndWithinDeadline(run);

                // 工具结果必须先归档，再执行工具并进入下一轮模型调用。
                if (result instanceof ModelInvocationResult.ToolCalls toolCalls) {
                    toolCallCount += toolCalls.calls().size();
                    // 在归档任何超限工具之前拒绝本轮结果。
                    if (toolCallCount > limits.getMaxToolCalls()) {
                        throw new ReactLimitExceededException(ERROR_TOOL_CALL_LIMIT);
                    }
                    List<PreparedToolInvocation> preparedTools =
                            modelInvocationCoordinator.archiveToolCalls(
                                    run, modelInvocation, toolCalls.calls());
                    tools.executeModelInvocationTools(
                            run, modelInvocation.modelInvocationSequence(), preparedTools);
                    continue;
                }

                // 非工具结果只能是最终回答；完成持久化后结束本次 ReAct 循环。
                ModelInvocationResult.FinalAnswer finalAnswer =
                        (ModelInvocationResult.FinalAnswer) result;
                modelInvocationCoordinator.completeFinalAnswer(
                        run, modelInvocation, finalAnswer.answer());
                return;
            } finally {
                // continue、正常返回与模型/工具异常都必须关闭本轮冻结快照。
                memoryInvocations.close(
                        run.executionId(), modelInvocation.modelInvocationSequence());
            }
        }
    }
}
