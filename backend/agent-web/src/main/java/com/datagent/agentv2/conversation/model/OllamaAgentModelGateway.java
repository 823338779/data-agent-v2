package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ToolCallDocument;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OllamaAgentModelGateway implements AgentModelGateway {

    private static final String OPTION_MAX_OUTPUT_TOKENS = "num_predict";
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
    public ModelTurnResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<ModelDelta> deltaConsumer) {
        String tenantId = TenantContext.requiredTenantId();
        OllamaApi.ChatRequest request =
                OllamaApi.ChatRequest.builder(properties.getModel())
                        .messages(messages.stream().map(this::toProviderMessage).toList())
                        .tools(toProviderTools(tools))
                        .options(Map.of(OPTION_MAX_OUTPUT_TOKENS, properties.getMaxOutputTokens()))
                        .enableThinking()
                        .stream(true)
                        .build();
        StreamAccumulator accumulator = new StreamAccumulator(deltaConsumer, tenantId);

        // 只有 provider 明确返回 done 后才允许把累积内容提升为完整 ModelTurnResult。
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
        OllamaApi.Message.Role role =
                OllamaApi.Message.Role.valueOf(message.role().toUpperCase(Locale.ROOT));
        OllamaApi.Message.Builder builder =
                OllamaApi.Message.builder(role).content(message.content());
        if (!message.toolCalls().isEmpty()) {
            builder.toolCalls(
                    message.toolCalls().stream()
                            .map(
                                    call ->
                                            new OllamaApi.Message.ToolCall(
                                                    call.id(),
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

    private final class StreamAccumulator {

        private final Consumer<ModelDelta> deltaConsumer;

        /** Tenant restored around callbacks that Reactor dispatches on provider event-loop threads. */
        private final String tenantId;

        private final StringBuilder answer = new StringBuilder();
        private final Map<String, ToolCallDocument> toolCalls = new LinkedHashMap<>();
        private boolean done;

        private StreamAccumulator(Consumer<ModelDelta> deltaConsumer, String tenantId) {
            this.deltaConsumer = deltaConsumer;
            this.tenantId = tenantId;
        }

        private void accept(OllamaApi.ChatResponse response) {
            OllamaApi.Message message = response.message();
            if (message != null) {
                appendReasoning(message.thinking());
                appendAnswer(message.content());
                appendToolCalls(message.toolCalls());
            }
            done = done || Boolean.TRUE.equals(response.done());
        }

        private void appendReasoning(String content) {
            if (content != null && !content.isEmpty()) {
                emit(ModelDelta.reasoning(content));
            }
        }

        private void appendAnswer(String content) {
            if (content != null && !content.isEmpty()) {
                answer.append(content);
                emit(ModelDelta.answer(content));
            }
        }

        private void emit(ModelDelta delta) {
            // Persistence checkpoints require the request tenant even on Reactor callback threads.
            TenantContext.run(tenantId, () -> deltaConsumer.accept(delta));
        }

        private void appendToolCalls(List<OllamaApi.Message.ToolCall> calls) {
            if (calls == null) {
                return;
            }
            for (OllamaApi.Message.ToolCall call : calls) {
                if (call.id() == null || call.id().isBlank() || call.function() == null) {
                    throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
                }
                ToolCallDocument document =
                        new ToolCallDocument(
                                call.id(),
                                call.function().name(),
                                json.valueToTree(call.function().arguments()));
                ToolCallDocument previous = toolCalls.putIfAbsent(call.id(), document);
                if (previous != null && !previous.equals(document)) {
                    throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
                }
            }
        }

        private ModelTurnResult result() {
            if (!done) {
                throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
            }
            if (!toolCalls.isEmpty() && !answer.isEmpty()) {
                throw new ModelStreamInterruptedException(ERROR_AMBIGUOUS);
            }
            if (!toolCalls.isEmpty()) {
                return new ModelTurnResult.ToolCalls(new ArrayList<>(toolCalls.values()));
            }
            if (!answer.isEmpty()) {
                return new ModelTurnResult.FinalAnswer(answer.toString());
            }
            throw new ModelStreamInterruptedException(ERROR_INTERRUPTED);
        }
    }
}
