package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.persistence.AuditMetaObjectHandler;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.persistence.json.ConversationProcessDocumentCodec;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapperImpl;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapperImpl;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.memory.MemoryEntryService;
import com.datagent.agentv2.persistence.service.memory.MemoryEvidenceService;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import com.datagent.agentv2.persistence.service.memory.MemorySuppressionService;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = WebMemoryEvidenceAdapterTest.Config.class)
class WebMemoryEvidenceAdapterTest {
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        AuditMetaObjectHandler.class,
        ConversationPersistenceMapperImpl.class,
        ConversationProcessDocumentCodec.class,
        ConversationMessageService.class,
        WebMemoryEvidenceAdapter.class,
        WebMemoryEvidenceMapperImpl.class,
        MemoryPersistenceMapperImpl.class,
        MemoryEntryService.class,
        MemoryStateService.class,
        MemoryOperationService.class,
        MemoryEvidenceService.class,
        MemorySuppressionService.class,
        MemoryReviewJobService.class,
        MemoryReviewLeaseService.class,
        MemoryService.class,
        MemoryWritePolicy.class,
        PrefixedUuidIdGenerator.class
    })
    static class Config {
        @Bean
        io.micrometer.core.instrument.MeterRegistry registry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @Bean
        MemoryContentPolicy contentPolicy() {
            return new MemoryContentPolicy(new MemoryProperties());
        }
    }

    @Autowired WebMemoryEvidenceAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryService memory;
    @Autowired MemoryOperationService operations;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!'),('other','tenant_local','other','!')");
        jdbc.update(
                "insert into conversation_session(conversation_session_id,tenant_id,title) values ('conversation','tenant_local','Chat'),('other','tenant_local','Other')");
        jdbc.update(
                "insert into conversation_message(conversation_message_id,tenant_id,conversation_session_id,role,content,conversation_message_sequence,author_user_id,version) values ('msg','tenant_local','conversation','user','真实正文',11,'user_admin',1)");
        jdbc.update(
                "insert into agent_execution(execution_id,tenant_id,conversation_session_id,triggering_conversation_message_id,status,request_idempotency_key,request_payload_hash,hard_deadline_at) values ('execution','tenant_local','conversation','msg','done','req',repeat('a',64),now()+interval '1 hour'),('old','tenant_local','conversation','msg','done','old',repeat('b',64),now()+interval '1 hour')");
        jdbc.update("update conversation_message set execution_id='execution'");
    }

    MemoryWriteContext main() {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.MAIN, "request", "execution", "conversation", 0L, null);
    }

    List<MemoryEvidence> resolve(MemoryWriteContext context, List<String> ids) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> adapter.resolve(context, ids));
    }

    @Test
    void currentEvidenceAndWriteUseRealStoredBodyAndVersion() {
        var current = adapter.currentUserEvidence("execution");
        assertThat(current).hasSize(1);
        assertThat(current.getFirst().content()).isEqualTo("真实正文");
        assertThat(current.getFirst().messageVersion()).isEqualTo(1);
        var result =
                memory.execute(
                        main(),
                        new MemoryCommand.Add(
                                MemoryKind.PERSON_PROFILE,
                                "中文",
                                MemoryWriteReason.USER_REQUESTED,
                                List.of("msg"),
                                false));
        assertThat(result.status()).isEqualTo(MemoryStatus.CREATED);
        assertThat(
                        jdbc.queryForObject(
                                "select message_version from personal_memory_evidence", Long.class))
                .isEqualTo(1);
    }

    @Test
    void invalidOrDeletedEvidenceProducesCommittedRejectionWithoutRollbackOnly() {
        for (String mutation :
                List.of(
                        "role='assistant'",
                        "author_user_id='other'",
                        "execution_id='old'",
                        "conversation_session_id='other'",
                        "version=0",
                        "is_deleted=true,deleted_at=localtimestamp",
                        "content=''")) {
            seed();
            jdbc.update("update conversation_message set " + mutation);
            assertThat(resolve(main(), List.of("msg"))).isEmpty();
            var result =
                    memory.execute(
                            main(),
                            new MemoryCommand.Add(
                                    MemoryKind.PERSON_PROFILE,
                                    "中文",
                                    MemoryWriteReason.USER_REQUESTED,
                                    List.of("msg"),
                                    false));
            assertThat(result.status()).isEqualTo(MemoryStatus.INVALID_REQUEST);
            assertThat(operations.find("request").outcome().status())
                    .isEqualTo(MemoryStatus.INVALID_REQUEST);
        }
    }

    @Test
    void resolutionParticipatesInTheCallersTransaction() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    "update conversation_message set content='事务内正文',version=2");
                            var evidence = adapter.resolve(main(), List.of("msg"));
                            assertThat(evidence).hasSize(1);
                            assertThat(evidence.getFirst().content()).isEqualTo("事务内正文");
                            assertThat(evidence.getFirst().messageVersion()).isEqualTo(2);
                            status.setRollbackOnly();
                        });
        assertThat(adapter.currentUserEvidence("execution").getFirst().content()).isEqualTo("真实正文");
    }

    @Test
    void missingIdsAndDeletedBetweenFreezeAndCommitAreRejected() {
        assertThat(adapter.currentUserEvidence("execution")).hasSize(1);
        jdbc.update("update conversation_message set is_deleted=true,deleted_at=localtimestamp");
        assertThat(resolve(main(), List.of("msg", "forged"))).isEmpty();
        assertThat(
                        memory.execute(
                                        main(),
                                        new MemoryCommand.Add(
                                                MemoryKind.PERSON_PROFILE,
                                                "中文",
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of("msg"),
                                                false))
                                .status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
    }

    @Test
    void backgroundReadsOnlyItsConversationAndExclusiveInclusiveClaimInterval() {
        var claim =
                new MemoryReviewClaim(
                        "job",
                        "conversation",
                        1,
                        1,
                        1,
                        "worker",
                        1,
                        10,
                        11,
                        Instant.now().plusSeconds(120));
        var context =
                new MemoryWriteContext(
                        MemoryWriteContext.Caller.BACKGROUND,
                        "bg",
                        null,
                        "conversation",
                        0L,
                        claim);
        assertThat(resolve(context, List.of("msg"))).hasSize(1);
        jdbc.update("update conversation_message set conversation_message_sequence=10");
        assertThat(resolve(context, List.of("msg"))).isEmpty();
        jdbc.update("update conversation_message set conversation_message_sequence=12");
        assertThat(resolve(context, List.of("msg"))).isEmpty();
        jdbc.update(
                "update conversation_message set conversation_message_sequence=11,conversation_session_id='other'");
        assertThat(resolve(context, List.of("msg"))).isEmpty();
    }
}
