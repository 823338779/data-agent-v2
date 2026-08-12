package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
public final class OllamaAgentModelGateway implements AgentModelGateway {

    private static final String MODEL_PROVIDER = "ollama";
    private static final String ERROR_INTERRUPTED =
            "Ollama stream ended before a complete model result";
    private static final String ERROR_AMBIGUOUS =
            "Ollama returned both tool calls and a final answer";

    private final OllamaApi api;
    private final OllamaModelProperties properties;
    private final ObjectMapper json;

    public OllamaAgentModelGateway(
            OllamaApi api, OllamaModelProperties properties, ObjectMapper json) {
        this.api = api;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public ModelInvocationResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        String tenantId = TenantContext.requiredTenantId();
        OllamaApi.ChatRequest request =
                OllamaApi.ChatRequest.builder(properties.getModel())
                        .messages(messages.stream().map(this::toProviderMessage).toList())
                        .tools(toProviderTools(tools))
                        .options(
                                OllamaChatOptions.builder()
                                        .numCtx(properties.getContextWindowTokens())
                                        .numPredict(properties.getMaxOutputTokens())
                                        .build())
                        .enableThinking()
                        .stream(true)
                        .build();
        ExecutionStreamAccumulator accumulator =
                new ExecutionStreamAccumulator(outputDeltaConsumer, tenantId);

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

    private OllamaApi.Message toProviderMessage(ModelInputMessage message) {
        OllamaApi.Message.Role role = OllamaApi.Message.Role.valueOf(message.role().name());
        OllamaApi.Message.Builder builder =
                OllamaApi.Message.builder(role).content(message.content());
        if (message.thinking() != null) {
            builder.thinking(message.thinking());
        }
        if (!message.toolCalls().isEmpty()) {
            builder.toolCalls(
                    message.toolCalls().stream()
                            .map(
                                    call ->
                                            new OllamaApi.Message.ToolCall(
                                                    call.toolCallId(),
                                                    new OllamaApi.Message.ToolCallFunction(
                                                            call.name(), toMap(call.arguments()))))
                            .toList());
        }
        if (message.toolName() != null) {
            builder.toolName(message.toolName());
        }
        return builder.build();
    }

    private static List<OllamaApi.ChatRequest.Tool> toProviderTools(
            List<ModelToolDefinition> tools) {
        return tools.stream()
                .map(
                        tool ->
                                new OllamaApi.ChatRequest.Tool(
                                        new OllamaApi.ChatRequest.Tool.Function(
                                                tool.name(),
                                                tool.description(),
                                                tool.parameters())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(tools.jackson.databind.JsonNode node) {
        return json.convertValue(node, Map.class);
    }

    private final class ExecutionStreamAccumulator {

        private final Consumer<ModelOutputDelta> outputDeltaConsumer;

        /**
         * Tenant restored around callbacks that Reactor dispatches on provider event-loop threads.
         */
        private final String tenantId;

        private final StringBuilder answer = new StringBuilder();
        private final Map<String, ModelToolCall> toolCalls = new LinkedHashMap<>();
        private boolean done;

        private ExecutionStreamAccumulator(
                Consumer<ModelOutputDelta> outputDeltaConsumer, String tenantId) {
            this.outputDeltaConsumer = outputDeltaConsumer;
            this.tenantId = tenantId;
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
            // Persistence checkpoints require the request tenant even on Reactor callback threads.
            TenantContext.run(tenantId, () -> outputDeltaConsumer.accept(outputDelta));
        }

        private void appendToolCalls(List<OllamaApi.Message.ToolCall> calls) {
            if (calls == null) {
                return;
            }
            for (OllamaApi.Message.ToolCall call : calls) {
                if (call.id() == null || call.id().isBlank() || call.function() == null) {
                    throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
                }
                ModelToolCall document =
                        new ModelToolCall(
                                call.id(),
                                call.function().name(),
                                json.valueToTree(call.function().arguments()));
                ModelToolCall previous = toolCalls.putIfAbsent(call.id(), document);
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
