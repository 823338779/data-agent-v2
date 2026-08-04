package com.datagent.agentv2.persistence.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class ConversationPersistenceMapperTest {

    private static final String SESSION_ID = "session-1";
    private static final String TITLE = "会话";
    private static final String TAG = "test";
    private static final String SESSION_TYPE = "interactive";
    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String EXECUTION_ID = "execution-1";
    private static final String MESSAGE_ID = "message-1";
    private static final String REQUEST_ID = "request-1";
    private static final String REQUEST_TYPE = "message_submission";
    private static final String IDEMPOTENCY_KEY = "key-1";
    private static final String PAYLOAD_HASH = "hash-1";
    private static final String STATUS = "queued";
    private static final String PHASE = "queued";
    private static final long EXECUTION_BUDGET_MS = 600_000L;

    private final ConversationPersistenceMapper mapper =
            Mappers.getMapper(ConversationPersistenceMapper.class);

    @Test
    void mapsBusinessFieldsWithoutRequiringPersistenceAuditFields() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        ConversationSession session =
                ConversationSession.create(SESSION_ID, TITLE, TAG, SESSION_TYPE, USER_ID, now);

        ConversationSessionEntity entity = mapper.toEntity(session);

        assertThat(entity.getConversationSessionId()).isEqualTo(SESSION_ID);
        assertThat(entity.getId()).isNull();
        assertThat(entity.getTenantId()).isNull();
        assertThat(entity.getConversationMessageSequence()).isZero();
    }

    @Test
    void updatesBusinessFieldsWithoutOverwritingPersistenceMetadata() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        LocalDateTime createdAt = LocalDateTime.parse("2026-08-02T08:00:00");
        ConversationSession session =
                ConversationSession.create(SESSION_ID, TITLE, TAG, SESSION_TYPE, USER_ID, now);
        ConversationSessionEntity entity = mapper.toEntity(session);
        entity.setId(42L);
        entity.setTenantId(TENANT_ID);
        entity.setCreatedAt(createdAt);

        session.allocateSubmissionSequences(2);
        mapper.updateEntity(session, entity);

        assertThat(entity.getId()).isEqualTo(42L);
        assertThat(entity.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getConversationMessageSequence()).isEqualTo(2L);
        assertThat(entity.getExecutionEventSequence()).isEqualTo(1L);
    }

    @Test
    void mapsQueuedExecutionInBothDirections() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        AgentExecution execution =
                AgentExecution.queued(
                        EXECUTION_ID,
                        SESSION_ID,
                        MESSAGE_ID,
                        USER_ID,
                        REQUEST_ID,
                        now,
                        REQUEST_TYPE,
                        IDEMPOTENCY_KEY,
                        PAYLOAD_HASH,
                        EXECUTION_BUDGET_MS,
                        STATUS,
                        PHASE);

        AgentExecutionEntity entity = mapper.toEntity(execution);
        AgentExecution mapped = mapper.toModel(entity);

        assertThat(mapped.getExecutionId()).isEqualTo(EXECUTION_ID);
        assertThat(mapped.getRequestIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(mapped.getExecutionAttemptNumber()).isZero();
        assertThat(mapped.getExecutionBudgetMs()).isEqualTo(EXECUTION_BUDGET_MS);
    }
}
