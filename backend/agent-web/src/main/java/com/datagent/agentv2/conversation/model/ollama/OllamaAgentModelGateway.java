package com.datagent.agentv2.conversation.model.ollama;

import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.model.api.ModelOutputDelta;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(
        prefix = "data-agent.model",
        name = "provider",
        havingValue = "ollama",
        matchIfMissing = true)
@RequiredArgsConstructor
public final class OllamaAgentModelGateway implements AgentModelGateway {

    private static final String MODEL_PROVIDER = "ollama";
    private static final String TOOL_CALL_ID_PREFIX = "ollama_tool_call";
    private static final String ERROR_INTERRUPTED =
            "Ollama stream ended before a complete model result";
    private static final String ERROR_AMBIGUOUS =
            "Ollama returned both tool calls and a final answer";

    private final OllamaApi api;
    private final OllamaModelProperties properties;
    private final ObjectMapper json;
    private final IdGenerator ids;

    @Override
    public ModelInvocationResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        OllamaApi.ChatRequest request =
                OllamaApi.ChatRequest.builder(properties.getModel())
                        .messages(
                                messages.stream()
                                        .map(
                                                message ->
                                                        OllamaModelStructMapper.INSTANCE
                                                                .toProviderMessage(message, json))
                                        .toList())
                        .tools(OllamaModelStructMapper.INSTANCE.toProviderTools(tools))
                        .options(
                                OllamaChatOptions.builder()
                                        .numCtx(properties.getContextWindowTokens())
                                        .numPredict(properties.getMaxOutputTokens())
                                        .build())
                        .enableThinking()
                        .stream(true)
                        .build();
        ExecutionStreamAccumulator accumulator =
                new ExecutionStreamAccumulator(outputDeltaConsumer);

        // 只有 provider 明确返回 done 后才允许把累积内容提升为完整 ModelInvocationResult。
        try {
            api.streamingChat(request)
                    .doOnNext(accumulator::accept)
                    .blockLast(properties.getTimeout());
        } catch (RuntimeException failure) {
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED, failure);
        }
        return accumulator.result();
    }

    private final class ExecutionStreamAccumulator {

        private final Consumer<ModelOutputDelta> outputDeltaConsumer;

        private final StringBuilder answer = new StringBuilder();
        private final Map<String, ModelToolCall> toolCalls = new LinkedHashMap<>();
        private boolean done;

        private ExecutionStreamAccumulator(Consumer<ModelOutputDelta> outputDeltaConsumer) {
            this.outputDeltaConsumer = outputDeltaConsumer;
        }

        private void accept(OllamaApi.ChatResponse response) {
            OllamaApi.Message message = response.message();
            if (message != null) {
                appendThinking(message.thinking());
                appendAnswer(message.content());
                appendToolCalls(message.toolCalls());
            }
            done = done || Boolean.TRUE.equals(response.done());
        }

        private void appendThinking(String content) {
            if (content != null && !content.isEmpty()) {
                emit(ModelOutputDelta.thinking(content, MODEL_PROVIDER, properties.getModel()));
            }
        }

        private void appendAnswer(String content) {
            if (content != null && !content.isEmpty()) {
                answer.append(content);
                emit(ModelOutputDelta.answer(content, MODEL_PROVIDER, properties.getModel()));
            }
        }

        private void emit(ModelOutputDelta outputDelta) {
            outputDeltaConsumer.accept(outputDelta);
        }

        private void appendToolCalls(List<OllamaApi.Message.ToolCall> calls) {
            if (calls == null) {
                return;
            }
            for (OllamaApi.Message.ToolCall call : calls) {
                if (call.function() == null) {
                    throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
                }
                String toolCallId =
                        call.id() == null || call.id().isBlank()
                                ? ids.next(TOOL_CALL_ID_PREFIX)
                                : call.id();
                ModelToolCall document =
                        new ModelToolCall(
                                toolCallId,
                                call.function().name(),
                                json.valueToTree(call.function().arguments()));
                ModelToolCall previous = toolCalls.putIfAbsent(toolCallId, document);
                if (previous != null && !previous.equals(document)) {
                    throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
                }
            }
        }

        private ModelInvocationResult result() {
            if (!done) {
                throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
            }
            if (!toolCalls.isEmpty() && !answer.isEmpty()) {
                throw new ModelStreamInterruptedException(ERROR_AMBIGUOUS);
            }
            if (!toolCalls.isEmpty()) {
                return new ModelInvocationResult.ToolCalls(new ArrayList<>(toolCalls.values()));
            }
            if (!answer.isEmpty()) {
                return new ModelInvocationResult.FinalAnswer(answer.toString());
            }
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
        }
    }
}
