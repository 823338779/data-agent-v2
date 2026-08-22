package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.conversation.model.ollama.OllamaContextSummaryModelAdapter;
import com.datagent.agentv2.conversation.model.ollama.OllamaModelProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实本机 Ollama 验证长来源能通过 LSM 结构化摘要与严格校验链路。 */
class OllamaLsmLongContextIntegrationTest {

    private static final String ENABLED_PROPERTY = "data.agent.ollama-long-context-test.enabled";
    private static final String BASE_URL_PROPERTY = "data.agent.ollama-long-context-test.base-url";
    private static final String MODEL_PROPERTY = "data.agent.ollama-long-context-test.model";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen3.5:9b";
    private static final String SOURCE_ID = "raw-1-12";
    private static final String FACT_PREFIX = "Confirmed project fact for turn ";
    private static final String USER_PREFIX = "User historical request for turn ";
    private static final String ASSISTANT_PREFIX = "Assistant historical response for turn ";
    private static final String FILLER =
            " The project owner confirmed this durable state and asked that it remain available"
                    + " for later planning, dependency checks, and final verification.";
    private static final int COMPLETED_TURNS = 12;
    private static final int MESSAGE_COUNT = COMPLETED_TURNS * 2;
    private static final int FILLER_REPETITIONS_PER_MESSAGE = 14;
    private static final int MAX_OUTPUT_TOKENS = 1_536;
    private static final int CONTEXT_WINDOW_TOKENS = 65_536;
    private static final int SAFETY_RESERVE_TOKENS = 4_096;
    private static final int MODEL_MAX_OUTPUT_TOKENS = 4_096;
    private static final int MIN_SOURCE_CHARACTERS = 40_000;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofMinutes(2);

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void summarizesLongConversationThroughProductionLsmValidationChain() {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY));

        // 使用与服务默认配置一致的模型和 context window，确保能复现生产摘要调用。
        String baseUrl = System.getProperty(BASE_URL_PROPERTY, DEFAULT_BASE_URL);
        String model = System.getProperty(MODEL_PROPERTY, DEFAULT_MODEL);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(PROVIDER_TIMEOUT);
        RestClient.Builder restClient = RestClient.builder().requestFactory(requestFactory);
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(restClient).build();
        OllamaModelProperties properties =
                new OllamaModelProperties(
                        model,
                        PROVIDER_TIMEOUT,
                        MODEL_MAX_OUTPUT_TOKENS,
                        CONTEXT_WINDOW_TOKENS,
                        SAFETY_RESERVE_TOKENS);

        // 组装多轮长上下文，并保留每轮可被摘要模型提取的稳定事实。
        List<ContextMessage> messages = new ArrayList<>();
        for (int turn = 1; turn <= COMPLETED_TURNS; turn++) {
            String fact = FACT_PREFIX + turn;
            messages.add(ContextMessage.user(longMessage(USER_PREFIX + turn + ". " + fact)));
            messages.add(
                    ContextMessage.assistant(
                            longMessage(ASSISTANT_PREFIX + turn + ". " + fact), null));
        }
        String sourceDocument = JsonMapper.builder().build().writeValueAsString(messages);
        assertThat(sourceDocument.length()).isGreaterThan(MIN_SOURCE_CHARACTERS);

        // 走生产 LSM generator：真实调用 Ollama，并执行 schema、coverage 与 token 上限校验。
        ConversationTokenEstimator estimator = new ConversationTokenEstimator();
        ContextSummaryGenerator generator =
                new ContextSummaryGenerator(
                        new OllamaContextSummaryModelAdapter(api, properties),
                        new ContextSummaryDocumentValidator(),
                        estimator,
                        new LsmSegmentKeyFactory());
        GenerationRequest request =
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, COMPLETED_TURNS),
                        new MessageCoverage(1, MESSAGE_COUNT),
                        List.of(SOURCE_ID),
                        sourceDocument);

        GeneratedSummary generated =
                generator.generate(request, LsmContextPolicySnapshot.defaults(5, 4));

        // provider 限制真实生成 token；持久化值保留更保守的字符级预算供后续装配使用。
        assertThat(generated.provider()).isEqualTo("ollama");
        assertThat(generated.model()).isEqualTo(model);
        assertThat(generated.document().coverage())
                .isEqualTo(
                        new ContextSummaryDocumentValidator.ContextSummaryDocument.Coverage(
                                1, COMPLETED_TURNS, 1, MESSAGE_COUNT));
        assertThat(generated.document().confirmedFacts()).isNotEmpty();
        assertThat(generated.estimatedTokenCount())
                .isPositive()
                .isLessThan(estimator.estimateSummaryDocument(sourceDocument));
        assertThat(generated.serializedJson()).isNotBlank();
    }

    private static String longMessage(String prefix) {
        return prefix + FILLER.repeat(FILLER_REPETITIONS_PER_MESSAGE);
    }
}
