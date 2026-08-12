package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 使用 Ollama 非流式、无工具调用实现中立摘要模型端口。 */
@Service
@ConditionalOnProperty(
        prefix = "data-agent.model",
        name = "provider",
        havingValue = "ollama",
        matchIfMissing = true)
public final class OllamaContextSummaryModelAdapter implements ContextSummaryModelPort {

    private static final String MODEL_PROVIDER = "ollama";
    private static final String SUMMARY_FAILED = "Ollama context summary request failed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OllamaApi api;
    private final OllamaModelProperties properties;

    public OllamaContextSummaryModelAdapter(OllamaApi api, OllamaModelProperties properties) {
        this.api = Objects.requireNonNull(api, "api");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** 一次性取得完整结构化正文，不注册 tools，也不产生用户可见 delta。 */
    @Override
    public ContextSummaryModelIdentity identity() {
        return new ContextSummaryModelIdentity(MODEL_PROVIDER, properties.getModel());
    }

    @Override
    public ContextSummaryResult summarize(ContextSummaryRequest summaryRequest) {
        ContextSummaryRequest request = Objects.requireNonNull(summaryRequest, "summaryRequest");
        List<OllamaApi.Message> messages =
                List.of(
                        OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM)
                                .content(request.systemPrompt())
                                .build(),
                        OllamaApi.Message.builder(OllamaApi.Message.Role.USER)
                                .content(request.sourceDocument())
                                .build());
        OllamaChatOptions options =
                OllamaChatOptions.builder()
                        .numCtx(properties.getContextWindowTokens())
                        .numPredict(request.maxOutputTokens())
                        .build();
        JsonNode outputSchema = parseOutputSchema(request.outputSchema());
        OllamaApi.ChatRequest providerRequest =
                new OllamaApi.ChatRequest(
                        properties.getModel(),
                        messages,
                        false,
                        outputSchema,
                        null,
                        null,
                        options.toMap(),
                        null);

        // provider 错误使用不含请求或响应正文的稳定边界，防止摘要来源泄漏。
        try {
            OllamaApi.ChatResponse response = api.chat(providerRequest);
            if (response == null
                    || !Boolean.TRUE.equals(response.done())
                    || response.message() == null
                    || response.message().content() == null) {
                throw new IllegalStateException(SUMMARY_FAILED);
            }
            String content = response.message().content();
            int estimatedTokens =
                    response.evalCount() == null
                            ? content.codePointCount(0, content.length())
                            : response.evalCount();
            return new ContextSummaryResult(
                    content, estimatedTokens, MODEL_PROVIDER, properties.getModel());
        } catch (RuntimeException failure) {
            throw new IllegalStateException(SUMMARY_FAILED);
        }
    }

    private static JsonNode parseOutputSchema(String outputSchema) {
        try {
            return JSON.readTree(outputSchema);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(SUMMARY_FAILED);
        }
    }
}
