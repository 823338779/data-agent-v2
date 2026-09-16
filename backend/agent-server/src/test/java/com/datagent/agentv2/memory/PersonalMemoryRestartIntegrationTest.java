package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.persistence.service.memory.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 同一隔离数据库上销毁并重建完整 Spring context，验证真正运行时恢复与租约接管。 */
class PersonalMemoryRestartIntegrationTest {
    @Test
    void closedCapabilitiesAndEntriesSurviveContextRestart() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            String id;
            try (var app = new PersonalMemoryTestSupport(postgres, "hierarchical")) {
                var memory = app.context.getBean(MemoryService.class);
                app.submit(app.session(), "请记住我是一名后端工程师");
                id = memory.snapshot().entries().getFirst().memoryId();
                memory.updateSettings("off", new MemorySettingsChange(0, false, false, false));
            }
            try (var app = new PersonalMemoryTestSupport(postgres, "hierarchical")) {
                var snapshot = app.context.getBean(MemoryService.class).snapshot();
                assertThat(snapshot.entries())
                        .extracting(MemoryEntry::memoryId)
                        .containsExactly(id);
                assertThat(snapshot.state().readEnabled()).isFalse();
                assertThat(snapshot.state().explicitWriteEnabled()).isFalse();
                assertThat(snapshot.state().autoLearningEnabled()).isFalse();
                assertThat(snapshot.state().settingsVersion()).isEqualTo(1);
                app.context
                        .getBean(MemoryService.class)
                        .updateSettings(
                                "read-again", new MemorySettingsChange(1, true, null, null));
                app.submit(app.session(), "重启后新会话");
                assertThat(app.gateway.inputs.getLast())
                        .anyMatch(m -> m.content().contains("用户是一名后端工程师"));
            }
        }
    }

    @Test
    void expiredTaskIsTakenOverAfterContextRestartAndOldTokenCannotFinishOrWrite() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            MemoryReviewClaim old;
            try (var app = new PersonalMemoryTestSupport(postgres, "hierarchical")) {
                var states = app.context.getBean(MemoryStateService.class);
                var jobs = app.context.getBean(MemoryReviewJobService.class);
                app.context
                        .getBean(TransactionTemplate.class)
                        .executeWithoutResult(
                                tx -> {
                                    states.lockCurrent();
                                    jobs.markCompleted(
                                            "restart-job",
                                            "restart-session",
                                            1,
                                            0,
                                            2,
                                            states.currentBusinessTime().minusSeconds(5));
                                });
                old =
                        app.context
                                .getBean(MemoryReviewCoordinator.class)
                                .claimDue("before-restart", 4)
                                .getFirst();
            }
            try (var app = new PersonalMemoryTestSupport(postgres, "hierarchical")) {
                app.jdbc.update(
                        "update personal_memory_review_job set lease_until=clock_timestamp()-interval '1 second'");
                app.jdbc.update(
                        "update personal_memory_review_lease set lease_until=clock_timestamp()-interval '1 second'");
                var coordinator = app.context.getBean(MemoryReviewCoordinator.class);
                var fresh = coordinator.claimDue("after-restart", 4).getFirst();
                assertThat(fresh.jobToken()).isGreaterThan(old.jobToken());
                assertThat(fresh.userToken()).isGreaterThan(old.userToken());
                assertThatThrownBy(() -> coordinator.finish(old, new MemoryReviewProgress(2, 0)))
                        .isInstanceOfSatisfying(
                                MemoryException.class,
                                e -> assertThat(e.code()).isEqualTo(MemoryStatus.LEASE_LOST));
                var result =
                        app.context
                                .getBean(MemoryService.class)
                                .execute(
                                        new MemoryWriteContext(
                                                MemoryWriteContext.Caller.BACKGROUND,
                                                "old-write",
                                                null,
                                                old.conversationId(),
                                                0L,
                                                old),
                                        new MemoryCommand.Add(
                                                MemoryKind.PERSON_PROFILE,
                                                "旧结果",
                                                MemoryWriteReason.AGENT_INFERRED,
                                                List.of("old-evidence"),
                                                false));
                assertThat(result.status()).isEqualTo(MemoryStatus.LEASE_LOST);
                assertThat(coordinator.finish(fresh, new MemoryReviewProgress(2, 0)))
                        .isEqualTo(MemoryReviewJob.Status.COMPLETED);
                assertThat(
                                app.context
                                        .getBean(MemoryReviewJobService.class)
                                        .find("restart-job")
                                        .reviewedThroughSequence())
                        .isEqualTo(2);
            }
        }
    }
}
