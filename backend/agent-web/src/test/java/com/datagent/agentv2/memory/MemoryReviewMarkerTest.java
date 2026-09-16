package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.persistence.service.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 最终正文、DONE 与可靠任务标记使用同一真实数据库事务。 */
@SpringBootTest(
        classes = {WebMemoryEvidenceAdapterTest.Config.class, MemoryReviewMarkerTest.Config.class})
class MemoryReviewMarkerTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        MemoryReviewMarker.class,
        ModelInvocationCoordinator.class,
        AgentExecutionService.class,
        AgentExecutionStreamCheckpointService.class,
        ExecutionStreamCheckpointCoordinator.class,
        ConversationSessionService.class,
        AgentExecutionEventService.class
    })
    static class Config {
        @Bean
        MemoryProperties memoryProperties() {
            return new MemoryProperties();
        }

        // 本测试没有 thinking 项；隔离无关过程 UI 编排，消息/执行/流检查点均使用真实持久化。
        @Bean
        ConversationProcessCoordinator processes() {
            return mock(ConversationProcessCoordinator.class);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryService memory;
    @Autowired MemoryReviewMarker marker;
    @Autowired ModelInvocationCoordinator invocations;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
        memory.initialize();
        jdbc.update(
                "insert into conversation_session(conversation_session_id,tenant_id,title) values ('conversation','tenant_local','Chat')");
        jdbc.update(
                "insert into conversation_message(conversation_message_id,tenant_id,conversation_session_id,role,content,conversation_message_sequence,author_user_id,version) values ('msg','tenant_local','conversation','user','真实正文',11,'user_admin',1),('answer','tenant_local','conversation','assistant','',12,'user_admin',1)");
        jdbc.update(
                "insert into agent_execution(execution_id,tenant_id,conversation_session_id,triggering_conversation_message_id,status,request_idempotency_key,request_payload_hash,hard_deadline_at) values ('execution','tenant_local','conversation','msg','running','req',repeat('a',64),clock_timestamp()+interval '1 hour')");
        jdbc.update("update conversation_message set execution_id='execution'");
        jdbc.update(
                "insert into agent_execution_stream_checkpoint(tenant_id,execution_id,model_invocation_sequence,phase,output_delta_sequence,answer,version) values ('tenant_local','execution',1,'CONNECTING_MODEL',0,'',0)");
    }

    @Test
    void completedAnswerCreatesFirstRangeFromTriggeringUserMessage() {
        complete();
        assertThat(jdbc.queryForObject("select status from agent_execution", String.class))
                .isEqualTo("done");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_execution_event where type='execution_done'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select reviewed_through_sequence from personal_memory_review_job",
                                Long.class))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "select pending_through_sequence from personal_memory_review_job",
                                Long.class))
                .isEqualTo(12);
        var due =
                jdbc.queryForObject(
                        "select available_at from personal_memory_review_job", LocalDateTime.class);
        var now =
                jdbc.queryForObject(
                        "select clock_timestamp() at time zone 'Asia/Shanghai'",
                        LocalDateTime.class);
        assertThat(due).isAfter(now.plusSeconds(25));
    }

    @Test
    void markerDatabaseFailureRollsBackFinalAnswerDoneAndCheckpoint() {
        jdbc.execute(
                "alter table personal_memory_review_job add constraint reject_test_job check (false)");
        try {
            assertThatThrownBy(this::complete).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select status from agent_execution", String.class))
                    .isEqualTo("running");
            assertThat(
                            jdbc.queryForObject(
                                    "select content from conversation_message where role='assistant'",
                                    String.class))
                    .isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "select answer from agent_execution_stream_checkpoint",
                                    String.class))
                    .isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from agent_execution_event", Integer.class))
                    .isZero();
            assertThat(jobCount()).isZero();
        } finally {
            jdbc.execute("alter table personal_memory_review_job drop constraint reject_test_job");
        }
    }

    @Test
    void legacyEvidenceTimeDoesNotFallBackToApplicationAuditClock() {
        jdbc.update(
                "update conversation_message set memory_evidence_created_at=null, created_at='2099-01-01' where role='user'");
        complete();
        assertThat(jobCount()).isZero();
    }

    @Test
    void oldRoundCompletingAfterReenableIsNotNewLearningEvidence() {
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        memory.updateSettings("on", new MemorySettingsChange(1, null, null, true));
        jdbc.update(
                "update conversation_message set memory_evidence_created_at=(select learning_enabled_at-interval '1 second' from personal_memory_state), created_at='2099-01-01' where role='user'");
        complete();
        assertThat(jobCount()).isZero();
    }

    @Test
    void disabledOrUnfinishedExecutionDoesNotCreateJob() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> marker.markCompleted("execution"));
        assertThat(jobCount()).isZero();
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        complete();
        assertThat(jobCount()).isZero();
    }

    @Test
    void markerRequiresCallersTransaction() {
        assertThatThrownBy(() -> marker.markCompleted("execution"))
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
    }

    void complete() {
        invocations.completeFinalAnswer(
                new LocalAgentExecution("tenant_local", "conversation", "execution", 11),
                new ModelInvocationCoordinator.ModelInvocationBoundary(1, 11),
                "完成回答");
    }

    int jobCount() {
        return jdbc.queryForObject(
                "select count(*) from personal_memory_review_job", Integer.class);
    }
}
