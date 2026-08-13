package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.AgentV2Application;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.conversation.cluster.RedisExecutionCancellationListener;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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

/** 跨模块验收 LSM 压缩触发、水位、历史重放和层级边界。 */
@SpringBootTest(
        classes = LsmContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=lsm",
            "data-agent.runtime.scheduling-enabled=false",
            "data-agent.auth.bootstrap.enabled=false"
        })
class LsmContextCompactionIntegrationTest {

    private static final String TENANT_ID = "tenant_acceptance";
    private static final String SESSION_ID = "session_acceptance";
    private static final String FIRST_EXECUTION_ID = "execution_first";
    private static final String SECOND_EXECUTION_ID = "execution_second";
    private static final String OWNER_ID = "owner_acceptance";
    private static final String FIRST_QUESTION = "first question";
    private static final String FIRST_ANSWER = "first answer";
    private static final String SECOND_QUESTION = "second question";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String USER_ID = "user_acceptance";
    private static final String TEST_PASSWORD = "unused";
    private static final String SESSION_TYPE = "chat";
    private static final String HISTORY_PAYLOAD = "history payload ".repeat(24);
    private static final int COMPLETED_TURNS_FOR_TRIGGER = 20;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmConversationContextStrategy realStrategy;
    @Autowired private LsmConversationContextAssembler realAssembler;
    @Autowired private LsmExecutionPolicyRepository policies;
    @Autowired private DeterministicSummaryModel summaryModel;

    @BeforeEach
    void seedConversation() {
        summaryModel.reset();
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (tenant_id, name) values (?, ?)", TENANT_ID, TENANT_ID);
        jdbc.update(
                "insert into user_account (user_id, tenant_id, username, password_hash) values (?,"
                        + " ?, ?, ?)",
                USER_ID,
                TENANT_ID,
                USER_ID,
                TEST_PASSWORD);
        insertSession();
        insertMessage("message-1", FIRST_EXECUTION_ID, ROLE_USER, FIRST_QUESTION, 1L);
        insertExecution(FIRST_EXECUTION_ID, "message-1", "done", "COMPLETED", null, 1L);
        insertMessage("message-2", FIRST_EXECUTION_ID, ROLE_ASSISTANT, FIRST_ANSWER, 2L);
        insertMessage("message-3", SECOND_EXECUTION_ID, ROLE_USER, SECOND_QUESTION, 3L);
        insertExecution(
                SECOND_EXECUTION_ID, "message-3", "running", "PREPARING_CONTEXT", OWNER_ID, 1L);
    }

    private void insertSession() {
        jdbc.update(
                "insert into conversation_session (conversation_session_id, tenant_id, title,"
                        + " session_type, created_by_user_id) values (?, ?, ?, ?, ?)",
                SESSION_ID,
                TENANT_ID,
                "Acceptance",
                SESSION_TYPE,
                USER_ID);
    }

    private long replaceWithCompletedHistory(int completedTurns) {
        jdbc.execute("truncate table conversation_session cascade");
        insertSession();
        for (int turn = 1; turn <= completedTurns; turn++) {
            String executionId = "execution-history-" + turn;
            long questionSequence = turn * 2L - 1L;
            long answerSequence = turn * 2L;
            String questionId = "message-" + questionSequence;
            insertMessage(
                    questionId,
                    executionId,
                    ROLE_USER,
                    "question-" + turn + " " + HISTORY_PAYLOAD,
                    questionSequence);
            insertExecution(executionId, questionId, "done", "COMPLETED", null, 1L);
            insertMessage(
                    "message-" + answerSequence,
                    executionId,
                    ROLE_ASSISTANT,
                    "answer-" + turn + " " + HISTORY_PAYLOAD,
                    answerSequence);
        }
        long currentSequence = completedTurns * 2L + 1L;
        String currentMessageId = "message-" + currentSequence;
        insertMessage(
                currentMessageId, SECOND_EXECUTION_ID, ROLE_USER, SECOND_QUESTION, currentSequence);
        insertExecution(
                SECOND_EXECUTION_ID,
                currentMessageId,
                "running",
                "PREPARING_CONTEXT",
                OWNER_ID,
                1L);
        return currentSequence;
    }

    private void insertMessage(
            String messageId, String executionId, String role, String content, long sequence) {
        jdbc.update(
                "insert into conversation_message (conversation_message_id, tenant_id,"
                        + " conversation_session_id, role, content, conversation_message_sequence,"
                        + " author_user_id) values (?, ?, ?, ?, ?, ?, ?)",
                messageId,
                TENANT_ID,
                SESSION_ID,
                role,
                content,
                sequence,
                ROLE_USER.equals(role) ? USER_ID : null);
        if (jdbc.queryForObject(
                        "select count(*) from agent_execution where execution_id = ?",
                        Integer.class,
                        executionId)
                > 0) {
            jdbc.update(
                    "update conversation_message set execution_id = ? where"
                            + " conversation_message_id = ?",
                    executionId,
                    messageId);
        }
    }

    private void insertExecution(
            String executionId,
            String triggeringMessageId,
            String status,
            String phase,
            String owner,
            long attempt) {
        java.time.Instant now = java.time.Instant.now();
        boolean running = owner != null;
        jdbc.update(
                "insert into agent_execution (execution_id, tenant_id, conversation_session_id,"
                        + " triggering_conversation_message_id, status, phase, execution_attempt_number,"
                        + " owner_user_id, owner_instance_id, request_id, queued_at, started_at,"
                        + " heartbeat_at, lease_expires_at, active_segment_started_at, hard_deadline_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                executionId,
                TENANT_ID,
                SESSION_ID,
                triggeringMessageId,
                status,
                phase,
                attempt,
                USER_ID,
                owner,
                "request-" + executionId,
                java.sql.Timestamp.from(now.minusSeconds(2)),
                java.sql.Timestamp.from(now.minusSeconds(1)),
                running ? java.sql.Timestamp.from(now) : null,
                running ? java.sql.Timestamp.from(now.plusSeconds(60)) : null,
                running ? java.sql.Timestamp.from(now) : null,
                running ? java.sql.Timestamp.from(now.plusSeconds(600)) : null);
        jdbc.update(
                "update conversation_message set execution_id = ? where conversation_message_id = ?",
                executionId,
                triggeringMessageId);
    }

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", LsmContextPostgresIntegrationSupport.POSTGRES::getJdbcUrl);
        registry.add(
                "spring.datasource.username",
                LsmContextPostgresIntegrationSupport.POSTGRES::getUsername);
        registry.add(
                "spring.datasource.password",
                LsmContextPostgresIntegrationSupport.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
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

    static final class DeterministicSummaryModel implements ContextSummaryModelPort {

        private static final String MODEL_PROVIDER = "fake";
        private static final String MODEL_NAME = "deterministic";
        private static final Pattern START_TURN = Pattern.compile("startTurnSequence=(\\d+)");
        private static final Pattern END_TURN = Pattern.compile("endTurnSequence=(\\d+)");
        private static final Pattern START_MESSAGE = Pattern.compile("startMessageSequence=(\\d+)");
        private static final Pattern END_MESSAGE = Pattern.compile("endMessageSequence=(\\d+)");
        private static final String SUMMARY_DOCUMENT =
                """
                {"schemaVersion":1,"coverage":{"startTurnSequence":%d,"endTurnSequence":%d,"startMessageSequence":%d,"endMessageSequence":%d},"confirmedFacts":[],"userPreferences":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"openQuestions":[],"nextSteps":[]}
                """;
        private final java.util.concurrent.atomic.AtomicInteger invocations =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ContextSummaryModelIdentity identity() {
            return new ContextSummaryModelIdentity(MODEL_PROVIDER, MODEL_NAME);
        }

        @Override
        public ContextSummaryResult summarize(ContextSummaryRequest request) {
            invocations.incrementAndGet();
            String prompt = request.systemPrompt();
            return new ContextSummaryResult(
                    SUMMARY_DOCUMENT.formatted(
                            extract(START_TURN, prompt),
                            extract(END_TURN, prompt),
                            extract(START_MESSAGE, prompt),
                            extract(END_MESSAGE, prompt)),
                    40,
                    MODEL_PROVIDER,
                    MODEL_NAME);
        }

        private static long extract(Pattern pattern, String prompt) {
            var matcher = pattern.matcher(prompt);
            if (!matcher.find()) {
                throw new IllegalArgumentException("trusted coverage is missing");
            }
            return Long.parseLong(matcher.group(1));
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }

    @Test
    void secondExecutionReadsHistoryProducedByFirstExecution() {
        PreparedConversationContext prepared =
                TenantContext.call(
                        TENANT_ID,
                        () -> realStrategy.prepare(request(SECOND_EXECUTION_ID, 3L, 5, 4)));

        assertThat(prepared.messages())
                .extracting(ContextMessage::content)
                .containsExactly(FIRST_QUESTION, FIRST_ANSWER, SECOND_QUESTION);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_lsm_execution_policy"
                                        + " where execution_id = ?",
                                Integer.class,
                                SECOND_EXECUTION_ID))
                .isEqualTo(1);
    }

    @Test
    void seventyFivePercentPlusOneTriggersCompactionAndReturnsAtMostTargetWatermark() {
        long inputBoundary = replaceWithCompletedHistory(COMPLETED_TURNS_FOR_TRIGGER);
        ConversationContextRequest measurementRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        5,
                        4,
                        new ContextBudget(1_000_000, 1, 1, 0));
        int actualTokens =
                TenantContext.call(
                                TENANT_ID,
                                () ->
                                        realAssembler.loadAndEstimate(
                                                measurementRequest,
                                                LsmContextPolicySnapshot.defaults(5, 4)))
                        .estimatedTokens();
        ConversationContextRequest triggerRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        5,
                        4,
                        budgetWithSoftTrigger(actualTokens - 1));

        var triggerState =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                realAssembler.loadAndEstimate(
                                        triggerRequest, LsmContextPolicySnapshot.defaults(5, 4)));
        PreparedConversationContext prepared =
                TenantContext.call(TENANT_ID, () -> realStrategy.prepare(triggerRequest));
        PreparedConversationContext reread =
                TenantContext.call(TENANT_ID, () -> realStrategy.prepare(triggerRequest));

        assertThat(triggerState.estimatedTokens())
                .isEqualTo(LsmContextPolicySnapshot.defaults(5, 4).softTriggerTokens(triggerRequest.budget()) + 1);
        assertThat(prepared.compacted()).isTrue();
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(LsmContextPolicySnapshot.defaults(5, 4).targetInputTokens(triggerRequest.budget()));
        assertThat(reread.estimatedInputTokens()).isEqualTo(prepared.estimatedInputTokens());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_lsm_job where tenant_id"
                                        + " = ? and execution_id = ? and status = 'PUBLISHED'",
                                Integer.class,
                                TENANT_ID,
                                SECOND_EXECUTION_ID))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_lsm_segment where"
                                        + " tenant_id = ? and conversation_session_id = ?",
                                Integer.class,
                                TENANT_ID,
                                SESSION_ID))
                .isPositive();
        assertThat(
                        jdbc.queryForMap(
                                "select generation, evaluated_up_to_message_sequence,"
                                        + " jsonb_array_length(active_segment_ids) as active_count"
                                        + " from conversation_context_lsm_manifest where tenant_id = ?"
                                        + " and conversation_session_id = ?",
                                TENANT_ID,
                                SESSION_ID))
                .containsEntry("generation", 1L)
                .containsEntry("evaluated_up_to_message_sequence", inputBoundary)
                .containsEntry("active_count", 1);
    }

    private static ContextBudget budgetWithSoftTrigger(int expectedSoftTrigger) {
        int contextWindow = 1;
        while ((int) Math.floor(contextWindow * 0.75d) != expectedSoftTrigger) {
            contextWindow++;
        }
        return new ContextBudget(contextWindow, 1, 1, 0);
    }

    @Test
    void warmPublishesWithinConfiguredRangeAndHotKeepsTenCompleteTurns() {
        long inputBoundary = replaceWithCompletedHistory(127);
        ConversationContextRequest measurementRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        5,
                        4,
                        new ContextBudget(1_000_000, 1, 1, 0));
        int actualTokens =
                TenantContext.call(
                                TENANT_ID,
                                () ->
                                        realAssembler.loadAndEstimate(
                                                measurementRequest,
                                                LsmContextPolicySnapshot.defaults(5, 4)))
                        .estimatedTokens();
        ConversationContextRequest triggerRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        5,
                        4,
                        budgetWithSoftTrigger(actualTokens - 1));

        // 真实发布后再次走 strategy，确保 Warm/Hot 边界来自 PostgreSQL manifest 重读。
        PreparedConversationContext prepared =
                TenantContext.call(TENANT_ID, () -> realStrategy.prepare(triggerRequest));
        PreparedConversationContext reread =
                TenantContext.call(TENANT_ID, () -> realStrategy.prepare(triggerRequest));
        var manifest =
                jdbc.queryForMap(
                        "select warm_cutoff_turn_sequence, hot_from_turn_sequence,"
                                + " pending_raw_start_turn_sequence, pending_raw_end_turn_sequence,"
                                + " generation from conversation_context_lsm_manifest where tenant_id"
                                + " = ? and conversation_session_id = ?",
                        TENANT_ID,
                        SESSION_ID);

        assertThat(manifest)
                .containsEntry("warm_cutoff_turn_sequence", 117L)
                .containsEntry("hot_from_turn_sequence", 118L)
                .containsEntry("pending_raw_start_turn_sequence", 111L)
                .containsEntry("pending_raw_end_turn_sequence", 117L);
        assertThat(117L - 11L + 1L).isBetween(100L, 109L);
        assertThat(reread.strategyGeneration()).isEqualTo(prepared.strategyGeneration());
        assertThat(reread.messages())
                .extracting(ContextMessage::content)
                .contains(
                        "question-118 " + HISTORY_PAYLOAD,
                        "answer-118 " + HISTORY_PAYLOAD,
                        "question-127 " + HISTORY_PAYLOAD,
                        "answer-127 " + HISTORY_PAYLOAD)
                .doesNotContain("question-1 " + HISTORY_PAYLOAD, "answer-110 " + HISTORY_PAYLOAD);
    }

    @Test
    void configurableFanoutAndMaximumLevelProduceOneAdvancingRoot() {
        assertPublishedRoot(3, 3);
        assertPublishedRoot(5, 5);

        // 已有 ROOT 与两个相邻同层尾段由真实 coordinator 滚动为唯一新 ROOT。
        long inputBoundary = replaceWithCompletedHistory(60);
        String oldRoot = "root-old";
        seedDurableSegment(oldRoot, "ROOT", 3, 1L, 30L, List.of());
        seedDurableSegment("tail-31", "STANDARD", 2, 31L, 40L, List.of());
        seedDurableSegment("tail-41", "STANDARD", 2, 41L, 50L, List.of());
        seedManifest(List.of(oldRoot, "tail-31", "tail-41"), oldRoot, 3, 3);

        PreparedConversationContext advanced = publishTriggered(inputBoundary, 3, 3);
        PublishedRoot published = publishedRoot();

        assertThat(advanced.strategyGeneration()).isEqualTo(8L);
        assertThat(published.rootId()).isNotEqualTo(oldRoot);
        assertThat(published.activeCount()).isEqualTo(1);
        assertThat(published.kind()).isEqualTo("ROOT");
        assertThat(published.level()).isEqualTo(3);
        assertThat(published.startTurn()).isEqualTo(1L);
        assertThat(published.endTurn()).isEqualTo(50L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_lsm_segment where"
                                        + " summary_segment_id = ?",
                                Integer.class,
                                oldRoot))
                .isEqualTo(1);
    }

    private void assertPublishedRoot(int fanout, int maxLevel) {
        long inputBoundary = replaceWithCompletedHistory(fanout * 10 + 10);
        List<String> children = new ArrayList<>();
        for (int index = 0; index < fanout; index++) {
            long start = index * 10L + 1L;
            String childId = "child-" + fanout + "-" + start;
            children.add(childId);
            seedDurableSegment(childId, "STANDARD", maxLevel - 1, start, start + 9L, List.of());
        }
        seedManifest(children, null, fanout, maxLevel);

        PreparedConversationContext prepared = publishTriggered(inputBoundary, fanout, maxLevel);
        PublishedRoot published = publishedRoot();

        assertThat(prepared.strategyGeneration()).isEqualTo(8L);
        assertThat(published.activeCount()).isEqualTo(1);
        assertThat(published.kind()).isEqualTo("ROOT");
        assertThat(published.level()).isEqualTo(maxLevel);
        assertThat(published.startTurn()).isEqualTo(1L);
        assertThat(published.endTurn()).isEqualTo(fanout * 10L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_lsm_segment where"
                                        + " summary_segment_id like ?",
                                Integer.class,
                                "child-" + fanout + "-%"))
                .isEqualTo(fanout);
    }

    private PreparedConversationContext publishTriggered(
            long inputBoundary, int fanout, int maxLevel) {
        ConversationContextRequest measurementRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        fanout,
                        maxLevel,
                        new ContextBudget(1_000_000, 1, 1, 0));
        LsmContextPolicySnapshot policy =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                policies.findOrCreateExecutionPolicy(
                                        measurementRequest.execution(),
                                        new LsmContextPolicySnapshot(
                                                1, fanout, maxLevel, 10, 75, 65, 100, 109, 10)));
        int actualTokens =
                TenantContext.call(
                                TENANT_ID,
                                () -> realAssembler.loadAndEstimate(measurementRequest, policy))
                        .estimatedTokens();
        ConversationContextRequest triggerRequest =
                request(
                        SECOND_EXECUTION_ID,
                        inputBoundary,
                        fanout,
                        maxLevel,
                        budgetWithSoftTrigger(actualTokens - 1));
        return TenantContext.call(TENANT_ID, () -> realStrategy.prepare(triggerRequest));
    }

    private void seedDurableSegment(
            String id,
            String kind,
            int level,
            long startTurn,
            long endTurn,
            List<String> sourceIds) {
        String digest = "%064x".formatted(Integer.toUnsignedLong(id.hashCode()));
        String document =
                DeterministicSummaryModel.SUMMARY_DOCUMENT.formatted(
                        startTurn, endTurn, startTurn * 2L - 1L, endTurn * 2L);
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbc.update(
                "insert into conversation_context_lsm_segment (summary_segment_id, tenant_id,"
                        + " conversation_session_id, segment_key, kind, level, start_turn_sequence,"
                        + " end_turn_sequence, start_message_sequence, end_message_sequence,"
                        + " partial_segment, summary_document, estimated_token_count,"
                        + " source_segment_ids, source_content_sha256, summary_schema_version,"
                        + " prompt_version, model_provider, model_name, creation_policy,"
                        + " source_segment_count, created_at, created_by, updated_at, updated_by,"
                        + " deleted, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?::jsonb,"
                        + " 40, ?::jsonb, ?, 1, 1, 'fixture', 'fixture', '{}'::jsonb, ?, ?, 'fixture',"
                        + " ?, 'fixture', false, 0)",
                id,
                TENANT_ID,
                SESSION_ID,
                digest,
                kind,
                level,
                startTurn,
                endTurn,
                startTurn * 2L - 1L,
                endTurn * 2L,
                document,
                new LsmDocumentCodec().writeStringList(sourceIds),
                digest,
                sourceIds.size(),
                now,
                now);
    }

    private void seedManifest(
            List<String> activeSegmentIds, String activeRootSegmentId, int fanout, int maxLevel) {
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbc.update(
                "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id,"
                        + " conversation_session_id, active_root_segment_id, active_segment_ids,"
                        + " warm_cutoff_turn_sequence, hot_from_turn_sequence,"
                        + " evaluated_up_to_message_sequence, effective_policy_version,"
                        + " effective_fanout, effective_max_level, generation, created_at, created_by,"
                        + " updated_at, updated_by, deleted, version) values (?, ?, ?, ?, ?::jsonb, 0,"
                        + " 0, 0, 1, ?, ?, 7, ?, 'fixture', ?, 'fixture', false, 0)",
                "manifest-fixture",
                TENANT_ID,
                SESSION_ID,
                activeRootSegmentId,
                new LsmDocumentCodec().writeStringList(activeSegmentIds),
                fanout,
                maxLevel,
                now,
                now);
    }

    private PublishedRoot publishedRoot() {
        var manifest =
                jdbc.queryForMap(
                        "select active_root_segment_id, jsonb_array_length(active_segment_ids) as"
                                + " active_count from conversation_context_lsm_manifest where tenant_id"
                                + " = ? and conversation_session_id = ?",
                        TENANT_ID,
                        SESSION_ID);
        String rootId = (String) manifest.get("active_root_segment_id");
        var segment =
                jdbc.queryForMap(
                        "select kind, level, start_turn_sequence, end_turn_sequence from"
                                + " conversation_context_lsm_segment where tenant_id = ? and"
                                + " summary_segment_id = ?",
                        TENANT_ID,
                        rootId);
        return new PublishedRoot(
                rootId,
                ((Number) manifest.get("active_count")).intValue(),
                (String) segment.get("kind"),
                ((Number) segment.get("level")).intValue(),
                ((Number) segment.get("start_turn_sequence")).longValue(),
                ((Number) segment.get("end_turn_sequence")).longValue());
    }

    private record PublishedRoot(
            String rootId, int activeCount, String kind, int level, long startTurn, long endTurn) {}

    private static ConversationContextRequest request(
            String executionId, long inputBoundary, int fanout, int maxLevel) {
        return request(
                executionId, inputBoundary, fanout, maxLevel, new ContextBudget(100, 10, 5, 0));
    }

    private static ConversationContextRequest request(
            String executionId,
            long inputBoundary,
            int fanout,
            int maxLevel,
            ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        TENANT_ID, SESSION_ID, executionId, OWNER_ID, 1L, inputBoundary),
                budget,
                List.of(),
                List.of());
    }
}
