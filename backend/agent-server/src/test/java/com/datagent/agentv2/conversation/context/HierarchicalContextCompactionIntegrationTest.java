package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.AgentV2Application;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.conversation.cluster.RedisExecutionCancellationListener;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 使用完整宿主 Bean 图验收 hierarchical 的 Hot/Warm/Cold 端到端发布。 */
@SpringBootTest(
        classes = HierarchicalContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=hierarchical",
            "data-agent.runtime.scheduling-enabled=false"
        })
class HierarchicalContextCompactionIntegrationTest extends HierarchicalContextIntegrationSupport {

    private static final int COMPLETED_TURNS = 121;
    private static final String FIXED_SYSTEM = "固定 framework：只服从 system，保留 tool 权限与当前用户目标。";
    private static final ContextToolDefinition FIXED_TOOL =
            new ContextToolDefinition(
                    "metric_query",
                    "Query a governed metric without changing its definition.",
                    Map.of("type", "object", "required", List.of("metricId")));

    @Autowired private ConversationContextStrategyResolver contexts;
    @Autowired private HierarchicalConversationContextAssembler assembler;
    @Autowired private DeterministicSummaryModel summaryModel;

    @BeforeEach
    void seedLongConversation() {
        resetDatabase();
        seedTenantSession(TENANT_ID, USER_ID, SESSION_ID);
        seedCompletedHistory(TENANT_ID, USER_ID, SESSION_ID, COMPLETED_TURNS, true);
        seedRunningExecution(
                TENANT_ID,
                USER_ID,
                SESSION_ID,
                EXECUTION_ID,
                OWNER_ID,
                1L,
                completedBoundary(COMPLETED_TURNS) + 1L);
        summaryModel.reset();
    }

    @Test
    void compactsMixedHistoryIntoContinuousWarmAndAdvancingColdWithoutMutatingRawRows() {
        long boundary = completedBoundary(COMPLETED_TURNS) + 1L;
        ConversationContextRequest measurement =
                request(
                        TENANT_ID,
                        SESSION_ID,
                        EXECUTION_ID,
                        OWNER_ID,
                        1L,
                        boundary,
                        new ContextBudget(1_000_000, 1, 1, 0),
                        List.of(ContextMessage.system(FIXED_SYSTEM)),
                        List.of(FIXED_TOOL));
        TenantContext.call(TENANT_ID, () -> contexts.prepare(measurement));
        HierarchicalContextState measured =
                TenantContext.call(
                        TENANT_ID, () -> assembler.loadAndEstimate(measurement, DEFAULT_POLICY));
        ConversationContextRequest trigger =
                request(
                        TENANT_ID,
                        SESSION_ID,
                        EXECUTION_ID,
                        OWNER_ID,
                        1L,
                        boundary,
                        budgetWithTrigger(measured.estimatedTokens() - 1),
                        measurement.fixedSystemMessages(),
                        measurement.toolDefinitions());
        String beforeDigest = rawDigest(TENANT_ID, SESSION_ID);
        int messageCountBefore = messageCount(TENANT_ID, SESSION_ID);

        PreparedConversationContext prepared =
                TenantContext.call(TENANT_ID, () -> contexts.prepare(trigger));
        PreparedConversationContext replayed =
                TenantContext.call(TENANT_ID, () -> contexts.prepare(trigger));

        // 75%+1 必须触发且只产生一对 body-free 生命周期事件；重放不得重复发布。
        assertThat(measured.estimatedTokens())
                .isEqualTo(DEFAULT_POLICY.softTriggerTokens(trigger.budget()) + 1);
        assertThat(prepared.compacted()).isTrue();
        assertThat(replayed.strategyGeneration()).isEqualTo(prepared.strategyGeneration());
        assertThat(compactionEventTypes())
                .containsExactly("context_compaction_started", "context_compaction_completed");
        assertThat(eventPayloads()).containsOnly("{}");

        // 本场景压缩后必须达到 70% 目标水位，并完整保留固定 framework、工具定义与十轮 Hot。
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(DEFAULT_POLICY.targetInputTokens(trigger.budget()));
        assertThat(prepared.messages().getFirst()).isEqualTo(ContextMessage.system(FIXED_SYSTEM));
        assertThat(trigger.toolDefinitions()).containsExactly(FIXED_TOOL);
        List<String> expectedHot =
                IntStream.rangeClosed(COMPLETED_TURNS - 9, COMPLETED_TURNS)
                        .boxed()
                        .flatMap(turn -> java.util.stream.Stream.of(question(turn), answer(turn)))
                        .toList();
        assertThat(
                        prepared.messages().stream()
                                .filter(
                                        message ->
                                                message.content().startsWith("中文问题")
                                                        || message.content().startsWith("中文回答"))
                                .map(ContextMessage::content))
                .containsExactlyElementsOf(expectedHot);
        assertThat(prepared.messages().getLast()).isEqualTo(ContextMessage.user(CURRENT_QUESTION));
        assertThat(
                        prepared.messages().stream()
                                .map(ContextMessage::thinking)
                                .filter(java.util.Objects::nonNull))
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(COMPLETED_TURNS - 9, COMPLETED_TURNS)
                                .mapToObj(HierarchicalContextIntegrationSupport::thinking)
                                .toList());

        // Warm frontier 必须无缝、无重叠覆盖 Cold 之后至 Hot 之前；超过 100 轮后 Cold 指针前进。
        Map<String, Object> checkpoint = checkpointRow(TENANT_ID, SESSION_ID);
        long coldTurns = ((Number) checkpoint.get("summarized_completed_turn_count")).longValue();
        long coldPointer =
                ((Number) checkpoint.get("summarized_up_to_conversation_message_sequence"))
                        .longValue();
        assertThat(coldTurns).isEqualTo(COMPLETED_TURNS - 100L);
        assertThat(coldPointer).isEqualTo(completedBoundary((int) coldTurns));
        assertThat(coldPointer).isPositive();
        assertWarmCoverage(
                TENANT_ID,
                SESSION_ID,
                (int) coldTurns + 1,
                COMPLETED_TURNS - DEFAULT_POLICY.recentRawTurns());

        // 压缩只增写私有表；原始正文和 thinking_process 均逐字保留。
        assertThat(messageCount(TENANT_ID, SESSION_ID)).isEqualTo(messageCountBefore);
        assertThat(rawDigest(TENANT_ID, SESSION_ID)).isEqualTo(beforeDigest);
        assertThat(summaryModel.sourceDocuments())
                .anyMatch(document -> document.contains("thinking"))
                .anyMatch(document -> document.contains("中文问题"))
                .anyMatch(
                        document ->
                                document.contains("metric_query")
                                        && document.contains(toolCallId(TOOL_HISTORY_TURN))
                                        && document.contains("revenue")
                                        && document.contains("42"));
    }

    private List<String> compactionEventTypes() {
        return jdbc.queryForList(
                "select type from agent_execution_event where tenant_id = ? and execution_id = ? and type like 'context_compaction_%' order by execution_event_sequence",
                String.class, TENANT_ID, EXECUTION_ID);
    }

    private List<String> eventPayloads() {
        return jdbc.queryForList(
                "select payload::text from agent_execution_event where tenant_id = ? and execution_id = ? and type like 'context_compaction_%' order by execution_event_sequence",
                String.class, TENANT_ID, EXECUTION_ID);
    }

    private void assertWarmCoverage(
            String tenantId, String sessionId, int expectedFirstTurn, int expectedLastTurn) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "select start_conversation_message_sequence, end_conversation_message_sequence from conversation_context_hierarchical_summary_node where tenant_id = ? and conversation_session_id = ? and hierarchical_summary_node_id in (select jsonb_array_elements_text(active_frontier_document -> 'nodes') from conversation_context_hierarchical_checkpoint where tenant_id = ? and conversation_session_id = ?) order by start_conversation_message_sequence",
                        tenantId,
                        sessionId,
                        tenantId,
                        sessionId);
        assertThat(rows).isNotEmpty();
        long next = completedBoundary(expectedFirstTurn - 1) + 1L;
        for (Map<String, Object> row : rows) {
            long start = ((Number) row.get("start_conversation_message_sequence")).longValue();
            long end = ((Number) row.get("end_conversation_message_sequence")).longValue();
            assertThat(start).isEqualTo(next);
            assertThat(end).isGreaterThanOrEqualTo(start);
            next = end + 1L;
        }
        assertThat(next - 1L).isEqualTo(completedBoundary(expectedLastTurn));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ConfigurationPropertiesScan("com.datagent.agentv2.conversation")
    @ComponentScan(
            basePackages = "com.datagent.agentv2",
            excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM,
                        classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = AgentV2Application.class),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = RedisExecutionCancellationListener.class)
            })
    static class TestApplication {

        @Bean
        @Primary
        ContextSummaryModelPort deterministicContextSummaryModel() {
            return new DeterministicSummaryModel();
        }
    }

    /** 返回可信 coverage 的确定性摘要，同时保留请求来源供端到端协议断言。 */
    static final class DeterministicSummaryModel implements ContextSummaryModelPort {

        private static final String PROVIDER = "test-provider";
        private static final String MODEL = "deterministic-hierarchical";
        private static final Pattern START_TURN = Pattern.compile("startTurn=(\\d+)");
        private static final Pattern END_TURN = Pattern.compile("endTurn=(\\d+)");
        private static final Pattern START_MESSAGE = Pattern.compile("startMessageSequence=(\\d+)");
        private static final Pattern END_MESSAGE = Pattern.compile("endMessageSequence=(\\d+)");
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> sourceDocuments = new CopyOnWriteArrayList<>();

        @Override
        public ContextSummaryModelIdentity identity() {
            return new ContextSummaryModelIdentity(PROVIDER, MODEL);
        }

        @Override
        public ContextSummaryResult summarize(ContextSummaryRequest request) {
            invocations.incrementAndGet();
            sourceDocuments.add(request.sourceDocument());
            long startTurn = extract(START_TURN, request.systemPrompt());
            long endTurn = extract(END_TURN, request.systemPrompt());
            long startMessage = extract(START_MESSAGE, request.systemPrompt());
            long endMessage = extract(END_MESSAGE, request.systemPrompt());
            String document =
                    """
                    {"schemaVersion":1,"coverage":{"startTurn":%d,"endTurn":%d,"startMessageSequence":%d,"endMessageSequence":%d},"confirmedFacts":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"nextSteps":[],"openQuestions":[]}
                    """
                            .formatted(startTurn, endTurn, startMessage, endMessage);
            return new ContextSummaryResult(document, 512, PROVIDER, MODEL);
        }

        List<String> sourceDocuments() {
            return List.copyOf(sourceDocuments);
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
            sourceDocuments.clear();
        }

        private static long extract(Pattern pattern, String source) {
            var matcher = pattern.matcher(source);
            if (!matcher.find()) {
                throw new IllegalArgumentException("trusted coverage is missing");
            }
            return Long.parseLong(matcher.group(1));
        }
    }
}

/** Task 11 的 hierarchical 宿主集成场景共享数据库夹具与真实私有迁移。 */
abstract class HierarchicalContextIntegrationSupport {

    protected static final String TENANT_ID = "tenant_hierarchical_acceptance";
    protected static final String USER_ID = "user_hierarchical_acceptance";
    protected static final String SESSION_ID = "session_hierarchical_acceptance";
    protected static final String EXECUTION_ID = "execution_hierarchical_acceptance";
    protected static final String OWNER_ID = "owner_hierarchical_acceptance";
    protected static final String CURRENT_QUESTION = "当前问题 current question";
    protected static final String UNUSED_PASSWORD = "unused";
    protected static final String EMPTY_SUMMARY = "{\"schemaVersion\":1}";
    protected static final String EMPTY_FRONTIER = "{\"schemaVersion\":1,\"nodes\":[]}";
    protected static final String MODEL_PROVIDER = "test-provider";
    protected static final String MODEL_NAME = "deterministic-hierarchical";
    protected static final String TOOL_NAME = "metric_query";
    protected static final String JSON_CONTENT_TYPE = "application/json";
    protected static final String SHA256_ZERO = "0".repeat(64);
    protected static final int TOOL_HISTORY_TURN = 50;
    protected static final HierarchicalContextPolicySnapshot DEFAULT_POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected HierarchicalCheckpointRepository checkpoints;
    @Autowired protected ObjectMapper json;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
    }

    protected void resetDatabase() {
        jdbc.execute("truncate table tenant cascade");
    }

    protected void seedTenantSession(String tenantId, String userId, String sessionId) {
        jdbc.update("insert into tenant (tenant_id, name) values (?, ?)", tenantId, tenantId);
        jdbc.update(
                "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?, ?, ?)",
                userId,
                tenantId,
                userId,
                UNUSED_PASSWORD);
        jdbc.update(
                "insert into conversation_session (conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, ?, ?, 'chat', ?)",
                sessionId,
                tenantId,
                "Hierarchical acceptance",
                userId);
    }

    protected void seedCompletedHistory(
            String tenantId,
            String userId,
            String sessionId,
            int completedTurns,
            boolean withThinking) {
        seedCompletedHistory(tenantId, userId, sessionId, completedTurns, withThinking, "");
    }

    /** 可选 ID 命名空间用于同业务 session/range 的跨租户真实历史夹具。 */
    protected void seedCompletedHistory(
            String tenantId,
            String userId,
            String sessionId,
            int completedTurns,
            boolean withThinking,
            String idNamespace) {
        for (int turn = 1; turn <= completedTurns; turn++) {
            String executionId = idNamespace + "history-execution-" + turn;
            long questionSequence = turn * 2L - 1L;
            long answerSequence = turn * 2L;
            String questionId = idNamespace + "question-message-" + turn;
            String answerId = idNamespace + "answer-message-" + turn;
            insertMessage(
                    tenantId,
                    userId,
                    sessionId,
                    questionId,
                    null,
                    "user",
                    question(turn),
                    "[]",
                    questionSequence);
            seedCompletedExecution(
                    tenantId, userId, sessionId, executionId, questionId, questionSequence);
            String thinkingDocument =
                    withThinking ? json.writeValueAsString(processHistory(turn)) : "[]";
            insertMessage(
                    tenantId,
                    userId,
                    sessionId,
                    answerId,
                    executionId,
                    "assistant",
                    answer(turn),
                    thinkingDocument,
                    answerSequence);
            if (withThinking && turn == TOOL_HISTORY_TURN) {
                seedCompletedToolExecution(
                        tenantId,
                        executionId,
                        answerId,
                        completedBoundary(turn),
                        toolProcessId(turn));
            }
        }
        insertMessage(
                tenantId,
                userId,
                sessionId,
                idNamespace + "current-question-message",
                null,
                "user",
                CURRENT_QUESTION,
                "[]",
                completedBoundary(completedTurns) + 1L);
    }

    protected void seedRunningExecution(
            String tenantId,
            String userId,
            String sessionId,
            String executionId,
            String ownerId,
            long attempt,
            long inputBoundary) {
        seedRunningExecution(
                tenantId,
                userId,
                sessionId,
                executionId,
                ownerId,
                attempt,
                inputBoundary,
                "current-question-message");
    }

    /** 允许跨租户夹具使用全局唯一消息业务 ID，同时保持相同 session/range。 */
    protected void seedRunningExecution(
            String tenantId,
            String userId,
            String sessionId,
            String executionId,
            String ownerId,
            long attempt,
            long inputBoundary,
            String triggeringMessageId) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into agent_execution (execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase, execution_attempt_number, owner_user_id, owner_instance_id, request_id, queued_at, started_at, heartbeat_at, lease_expires_at, active_segment_started_at, hard_deadline_at, execution_budget_ms, active_execution_ms, recovery_attempt_count) values (?, ?, ?, ?, 'running', 'PREPARING_CONTEXT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 600000, 0, 0)",
                executionId,
                tenantId,
                sessionId,
                triggeringMessageId,
                attempt,
                userId,
                ownerId,
                "request-" + executionId,
                Timestamp.from(now.minusSeconds(2)),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(120)),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)));
        jdbc.update(
                "insert into agent_execution_attempt (execution_attempt_id, tenant_id, execution_id, execution_attempt_number, owner_instance_id, status, started_at) values (?, ?, ?, ?, ?, 'running', ?)",
                "attempt-" + executionId + "-" + attempt,
                tenantId,
                executionId,
                attempt,
                ownerId,
                Timestamp.from(now.minusSeconds(1)));
        jdbc.update(
                "update conversation_message set execution_id = ? where tenant_id = ? and conversation_session_id = ? and conversation_message_sequence = ?",
                executionId,
                tenantId,
                sessionId,
                inputBoundary);

        // 模拟真实提交事务预建的唯一 assistant 中心消息，供执行失败收敛和 fencing 使用。
        insertMessage(
                tenantId,
                userId,
                sessionId,
                "assistant-center-" + executionId,
                executionId,
                "assistant",
                "",
                "[]",
                inputBoundary + 1L);
    }

    protected void seedInitialCheckpoint(String tenantId, String sessionId) {
        TenantContext.run(
                tenantId,
                () ->
                        checkpoints.create(
                                new HierarchicalCheckpoint(
                                        "checkpoint-" + tenantId,
                                        sessionId,
                                        EMPTY_SUMMARY,
                                        0L,
                                        0L,
                                        0,
                                        1,
                                        1,
                                        MODEL_PROVIDER,
                                        MODEL_NAME,
                                        EMPTY_FRONTIER,
                                        0L,
                                        0L)));
    }

    protected static ConversationContextRequest request(
            String tenantId,
            String sessionId,
            String executionId,
            String ownerId,
            long attempt,
            long inputBoundary,
            ContextBudget budget,
            List<ContextMessage> fixedMessages,
            List<ContextToolDefinition> tools) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        tenantId, sessionId, executionId, ownerId, attempt, inputBoundary),
                budget,
                fixedMessages,
                tools);
    }

    protected static ContextBudget budgetWithTrigger(int expectedTrigger) {
        int contextWindow = 1;
        while ((int) Math.floor(contextWindow * 0.75d) != expectedTrigger) {
            contextWindow++;
        }
        return new ContextBudget(contextWindow, 1, 1, 0);
    }

    protected static long completedBoundary(int completedTurns) {
        return completedTurns * 2L;
    }

    protected static String question(int turn) {
        return "中文问题 " + turn + " / English question " + "history payload ".repeat(18);
    }

    protected static String answer(int turn) {
        return "中文回答 " + turn + " / English answer " + "history result ".repeat(18);
    }

    protected static String thinking(int turn) {
        return "thinking-state-" + turn;
    }

    protected String rawDigest(String tenantId, String sessionId) {
        return jdbc.queryForObject(
                "select md5(string_agg(conversation_message_sequence::text || ':' || content || ':' || thinking_process::text, '|' order by conversation_message_sequence)) from conversation_message where tenant_id = ? and conversation_session_id = ?",
                String.class,
                tenantId,
                sessionId);
    }

    protected int messageCount(String tenantId, String sessionId) {
        return jdbc.queryForObject(
                "select count(*) from conversation_message where tenant_id = ? and conversation_session_id = ?",
                Integer.class,
                tenantId,
                sessionId);
    }

    protected Map<String, Object> checkpointRow(String tenantId, String sessionId) {
        return jdbc.queryForMap(
                "select summarized_completed_turn_count, summarized_up_to_conversation_message_sequence, strategy_generation, version, active_frontier_document from conversation_context_hierarchical_checkpoint where tenant_id = ? and conversation_session_id = ?",
                tenantId,
                sessionId);
    }

    private void seedCompletedExecution(
            String tenantId,
            String userId,
            String sessionId,
            String executionId,
            String questionId,
            long questionSequence) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into agent_execution (execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase, execution_attempt_number, owner_user_id, request_id, queued_at, started_at, completed_at, execution_budget_ms, active_execution_ms, recovery_attempt_count) values (?, ?, ?, ?, 'done', 'COMPLETED', 1, ?, ?, ?, ?, ?, 600000, 1, 0)",
                executionId,
                tenantId,
                sessionId,
                questionId,
                userId,
                "request-" + executionId,
                Timestamp.from(now.minusSeconds(2)),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now));
        jdbc.update(
                "update conversation_message set execution_id = ? where tenant_id = ? and conversation_session_id = ? and conversation_message_sequence = ?",
                executionId,
                tenantId,
                sessionId,
                questionSequence);
    }

    private void seedCompletedToolExecution(
            String tenantId,
            String executionId,
            String assistantMessageId,
            long inputBoundary,
            UUID processItemId) {
        Instant now = Instant.now();

        // 工具调用必须引用同一 execution 的 durable model invocation，保持历史夹具与生产约束一致。
        jdbc.update(
                "insert into model_invocation (model_invocation_id, tenant_id, execution_id, model_invocation_sequence, execution_attempt_number, input_up_to_conversation_message_sequence, status, started_at, completed_at) values (?, ?, ?, 1, 1, ?, 'completed', ?, ?)",
                "model-invocation-" + executionId,
                tenantId,
                executionId,
                inputBoundary,
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now));

        // TOOL ProcessItem 与工具执行行使用相同 UUID、调用标识和模型调用序号，允许 loader 完整复建 call/observation。
        jdbc.update(
                "insert into tool_execution (tenant_id, tool_execution_id, execution_id, model_invocation_sequence, execution_attempt_number, assistant_conversation_message_id, tool_process_item_id, tool_call_id, tool_name, status, execution_count, started_at, completed_at) values (?, ?, ?, 1, 1, ?, ?, ?, ?, 'succeeded', 1, ?, ?)",
                tenantId,
                toolExecutionId(TOOL_HISTORY_TURN),
                executionId,
                assistantMessageId,
                processItemId,
                toolCallId(TOOL_HISTORY_TURN),
                TOOL_NAME,
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now));
    }

    private void insertMessage(
            String tenantId,
            String userId,
            String sessionId,
            String messageId,
            String executionId,
            String role,
            String content,
            String thinkingDocument,
            long sequence) {
        jdbc.update(
                "insert into conversation_message (conversation_message_id, tenant_id, conversation_session_id, execution_id, role, content, thinking_process, conversation_message_sequence, author_user_id) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                messageId,
                tenantId,
                sessionId,
                executionId,
                role,
                content,
                thinkingDocument,
                sequence,
                "user".equals(role) ? userId : null);
    }

    private static ModelThinkingProcessItem thinkingItem(int turn) {
        Instant timestamp = Instant.parse("2026-08-13T00:00:00Z").plusSeconds(turn);
        return new ModelThinkingProcessItem(
                new UUID(0L, turn),
                1L,
                ThinkingProcessStatus.SUCCEEDED,
                timestamp,
                timestamp,
                timestamp,
                0L,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                thinking(turn),
                false,
                thinking(turn).length(),
                "%064x".formatted(turn),
                1L,
                MODEL_PROVIDER,
                MODEL_NAME);
    }

    private static List<ThinkingProcessItem> processHistory(int turn) {
        if (turn != TOOL_HISTORY_TURN) {
            return List.of(thinkingItem(turn));
        }
        Instant timestamp = Instant.parse("2026-08-13T00:00:00Z").plusSeconds(turn);
        BoundedProcessPayload input =
                new BoundedProcessPayload(
                        Map.of("metricId", "revenue"),
                        null,
                        false,
                        22L,
                        SHA256_ZERO,
                        JSON_CONTENT_TYPE);
        BoundedProcessPayload output =
                new BoundedProcessPayload(
                        Map.of("value", 42), null, false, 12L, SHA256_ZERO, JSON_CONTENT_TYPE);

        // 第一轮模型调用包含 thinking 与 tool，第二轮 thinking 作为最终回答关联的可恢复状态。
        return List.of(
                thinkingItem(turn),
                new ToolThinkingProcessItem(
                        toolProcessId(turn),
                        2L,
                        ThinkingProcessStatus.SUCCEEDED,
                        timestamp,
                        timestamp,
                        timestamp,
                        0L,
                        ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                        TOOL_NAME,
                        TOOL_NAME,
                        toolExecutionId(turn),
                        toolCallId(turn),
                        1L,
                        input,
                        output,
                        true,
                        null,
                        null),
                new ModelThinkingProcessItem(
                        new UUID(2L, turn),
                        3L,
                        ThinkingProcessStatus.SUCCEEDED,
                        timestamp,
                        timestamp,
                        timestamp,
                        0L,
                        ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                        thinking(turn) + "-final",
                        false,
                        thinking(turn).length() + 6L,
                        "%064x".formatted(turn + 1),
                        2L,
                        MODEL_PROVIDER,
                        MODEL_NAME));
    }

    private static UUID toolProcessId(int turn) {
        return new UUID(1L, turn);
    }

    protected static String toolExecutionId(int turn) {
        return "tool-execution-" + turn;
    }

    protected static String toolCallId(int turn) {
        return "tool-call-" + turn;
    }
}
