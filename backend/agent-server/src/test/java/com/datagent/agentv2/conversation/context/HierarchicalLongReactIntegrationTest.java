package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.context.hierarchical.domain.ContextExecutionFacts;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalStepSummaryDocument;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.tool.AgentTool;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实 HTTP、ReAct、工具归档和 PostgreSQL；只替换两个模型端口。 */
@SpringBootTest(
        properties = {
            "data-agent.context.strategy=hierarchical",
            "logging.level.agent.conversation.process.audit=OFF",
            "data-agent.react.limits.max-model-invocations=64",
            "data-agent.react.limits.max-tool-calls=64",
            "data-agent.tool.initial=fixture_echo",
            "data-agent.tool.maximum=fixture_echo,system_timezone,current_time,skill_read,web_search,web_fetch,memory"
        })
@AutoConfigureMockMvc
@Import(HierarchicalLongReactIntegrationTest.FixtureConfiguration.class)
class HierarchicalLongReactIntegrationTest {
    private static final String TOOL = "fixture_echo", MODEL = "fixture", CALL = "fixture-call-";
    private static final String SESSIONS = "/api/v1/agent-sessions", KEY_HEADER = "Idempotency-Key";
    private static final String RESULT = "读取资料完成，保留目标并继续；推送仍需用户确认。";
    private static final String LARGE = "安全测试内容".repeat(2000);
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AtomicInteger toolExecutions;
    @MockitoBean private AgentModelGateway model;
    @MockitoBean private ContextSummaryModelPort summaries;
    @MockitoBean private ContextModelBudgetProvider budgets;

    @Test
    void oneRequestCompletesFortyStepsWithTwoPublishedSummaries() throws Exception {
        runScenario(40, 1, "done", 40, 41);
    }

    @Test
    void compactionDoesNotResetReactLimits() throws Exception {
        runScenario(100, 1, "failed", 64, 64);
    }

    @Test
    void threeTurnsDoNotRequireNewSession() throws Exception {
        runScenario(40, 3, "done", 40, 43);
    }

    private void runScenario(
            int requestedTools,
            int turns,
            String expectedStatus,
            int expectedTools,
            int expectedCalls)
            throws Exception {
        toolExecutions.set(0);
        var calls = new AtomicInteger();
        var summaryCalls = new AtomicInteger();
        var reused = new AtomicInteger();
        when(budgets.currentBudget()).thenReturn(new ContextBudget(20000, 2048, 1024, 128));
        when(summaries.identity()).thenReturn(new ContextSummaryModelIdentity(MODEL, MODEL));
        when(summaries.summarize(any()))
                .thenAnswer(
                        invocation -> {
                            ContextSummaryRequest request = invocation.getArgument(0);
                            var source = json.readTree(request.sourceDocument());
                            var coverage =
                                    json.treeToValue(
                                            source.path("coverage"), StepSummaryCoverage.class);
                            var facts =
                                    json.treeToValue(
                                            source.path("executionFacts"),
                                            ContextExecutionFacts.class);
                            summaryCalls.incrementAndGet();
                            var summary =
                                    new HierarchicalStepSummaryDocument(
                                            3,
                                            coverage,
                                            List.of(
                                                    new HierarchicalStepSummaryDocument.Entry(
                                                            HierarchicalStepSummaryDocument.Kind
                                                                    .FACT,
                                                            RESULT,
                                                            List.of(coverage.throughInclusive()))),
                                            facts);
                            return new ContextSummaryResult(
                                    json.writeValueAsString(summary), 0, MODEL, MODEL);
                        });
        when(model.complete(anyList(), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            List<ModelInputMessage> messages = invocation.getArgument(0);
                            String encoded = json.writeValueAsString(messages);
                            if (encoded.contains(RESULT)) {
                                reused.incrementAndGet();
                            }
                            assertToolPairs(messages);
                            int step = calls.incrementAndGet();
                            if (step > requestedTools) {
                                return new ModelInvocationResult.FinalAnswer(RESULT);
                            }
                            return new ModelInvocationResult.ToolCalls(
                                    List.of(
                                            new ModelToolCall(
                                                    CALL + step,
                                                    TOOL,
                                                    json.createObjectNode()
                                                            .put("step", step)
                                                            .put("body", LARGE))));
                        });
        String session =
                json.readTree(
                                mvc.perform(
                                                post(SESSIONS)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"title\":\"单请求步骤压缩\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("conversationSessionId")
                        .asString();
        for (int turn = 0; turn < turns; turn++) {
            String execution =
                    json.readTree(
                                    mvc.perform(
                                                    post(SESSIONS + "/{session}/messages", session)
                                                            .header(KEY_HEADER, MODEL + turn)
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    "{\"content\":\"读取四十份材料，完成任务后等待确认\"}"))
                                            .andExpect(status().isAccepted())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .path("execution")
                            .path("executionId")
                            .asString();
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .until(
                            () ->
                                    !"running"
                                            .equals(
                                                    jdbc.queryForObject(
                                                            "select status from agent_execution where execution_id=?",
                                                            String.class,
                                                            execution)));
            assertThat(
                            jdbc.queryForObject(
                                    "select status from agent_execution where execution_id=?",
                                    String.class,
                                    execution))
                    .withFailMessage(
                            () ->
                                    jdbc.queryForObject(
                                            "select failure_code || ': ' || failure_detail from agent_execution where execution_id=?",
                                            String.class,
                                            execution))
                    .isEqualTo(expectedStatus);
        }
        assertThat(toolExecutions.get()).isEqualTo(expectedTools);
        assertThat(calls.get()).isEqualTo(expectedCalls);
        assertThat(summaryCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(reused.get()).isGreaterThanOrEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select strategy_generation from conversation_context_hierarchical_checkpoint where conversation_session_id=?",
                                Long.class,
                                session))
                .isGreaterThanOrEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_message where conversation_session_id=? and role='user'",
                                Integer.class,
                                session))
                .isEqualTo(turns);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_execution where conversation_session_id=?",
                                Integer.class,
                                session))
                .isEqualTo(turns);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_payload where conversation_session_id=?",
                                Integer.class,
                                session))
                .isEqualTo(expectedTools * 2);
    }

    private void assertToolPairs(List<ModelInputMessage> messages) {
        // 用供应商中立协议的序列化结构检查每个调用和结果，而非只检查总条数。
        var root = json.valueToTree(messages);
        var pending = new HashSet<String>();
        for (var message : root) {
            for (var call : message.path("toolCalls")) {
                assertThat(
                                pending.add(
                                        call.path("toolCallId")
                                                .asString(call.path("id").asString())))
                        .isTrue();
            }
            String result = message.path("toolCallId").asString(null);
            if (result != null) {
                assertThat(pending.remove(result)).isTrue();
            }
        }
        assertThat(pending).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        AtomicInteger toolExecutions() {
            return new AtomicInteger();
        }

        @Bean
        AgentTool fixtureEcho(ObjectMapper json, AtomicInteger toolExecutions) {
            return new AgentTool() {
                @Override
                public String name() {
                    return TOOL;
                }

                @Override
                public String description() {
                    return MODEL;
                }

                @Override
                public JsonNode parametersSchema() {
                    return json.readTree(
                            "{\"type\":\"object\",\"required\":[\"step\",\"body\"],\"properties\":{\"step\":{\"type\":\"integer\"},\"body\":{\"type\":\"string\"}}}");
                }

                @Override
                public JsonNode execute(JsonNode arguments, InvocationContext context) {
                    toolExecutions.incrementAndGet();
                    return json.createObjectNode()
                            .put("step", arguments.path("step").asInt())
                            .put("body", LARGE);
                }
            };
        }
    }
}
