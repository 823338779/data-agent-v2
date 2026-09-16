package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryLifecycleTest extends MemoryLifecycleSupport {
    @Test
    void evidenceKeepsFirstPromptVersionAcrossRepeatedConfirmation() {
        memory.initialize();
        var claim = claimFixture();
        stubEvidence(userEvidence("msg", "execution", 11));
        var first =
                memory.execute(
                        new MemoryWriteContext(
                                MemoryWriteContext.Caller.BACKGROUND,
                                "add",
                                null,
                                "conversation",
                                0L,
                                claim,
                                "review-v1"),
                        inferred("中文", "msg"));
        assertThat(entries.find(first.memoryId()).promptVersion()).isEqualTo("review-v1");
        memory.execute(
                new MemoryWriteContext(
                        MemoryWriteContext.Caller.BACKGROUND,
                        "confirm",
                        null,
                        "conversation",
                        1L,
                        claim,
                        "review-v2"),
                new MemoryCommand.Confirm(first.memoryId(), 1, List.of("msg")));
        assertThat(
                        jdbc.queryForObject(
                                "select prompt_version from personal_memory_evidence where evidence_id='msg'",
                                String.class))
                .isEqualTo("review-v1");
        stubEvidence(userEvidence("new", "execution", 12));
        memory.execute(
                new MemoryWriteContext(
                        MemoryWriteContext.Caller.BACKGROUND,
                        "fresh",
                        null,
                        "conversation",
                        1L,
                        claim,
                        "review-v2"),
                new MemoryCommand.Confirm(first.memoryId(), 1, List.of("new")));
        assertThat(
                        jdbc.queryForObject(
                                "select prompt_version from personal_memory_evidence where evidence_id='new'",
                                String.class))
                .isEqualTo("review-v2");
    }

    @Test
    void deletionRollsBackBodyEvidenceAndSuppressionWhenOperationCannotCommit() {
        var first = memory.execute(ui("add"), add("中文"));
        jdbc.execute(
                "create function reject_lifecycle_operation() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'");
        jdbc.execute(
                "create trigger reject_lifecycle before insert on personal_memory_operation for each row execute function reject_lifecycle_operation()");
        try {
            assertThatThrownBy(
                            () ->
                                    memory.execute(
                                            ui("delete"),
                                            new MemoryCommand.Remove(
                                                    first.memoryId(),
                                                    1,
                                                    MemoryWriteReason.USER_REQUESTED,
                                                    List.of())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(entries.find(first.memoryId())).isNotNull();
            assertThat(memory.snapshot().state().revision()).isEqualTo(1);
            assertThat(suppressions.find(contentPolicy.hash("中文"))).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from personal_memory_evidence", Integer.class))
                    .isEqualTo(1);
            assertThat(operations.find("delete")).isNull();
        } finally {
            jdbc.execute("drop trigger reject_lifecycle on personal_memory_operation");
            jdbc.execute("drop function reject_lifecycle_operation()");
        }
    }

    @Test
    void automaticRemovalSuppressesBothKindsAndCannotAutomaticallyRestore() {
        stubEvidence(userEvidence("msg", "execution", 11));
        var first = memory.execute(main("add", 0), inferred("中文", "msg"));
        assertThat(
                        memory.execute(
                                        main("delete", 1),
                                        new MemoryCommand.Remove(
                                                first.memoryId(),
                                                1,
                                                MemoryWriteReason.AGENT_INFERRED,
                                                List.of("msg")))
                                .status())
                .isEqualTo(MemoryStatus.REMOVED);
        for (var kind : MemoryKind.values()) {
            assertThat(
                            memory.execute(
                                            main("auto-" + kind, 2),
                                            new MemoryCommand.Add(
                                                    kind,
                                                    "中文",
                                                    MemoryWriteReason.AGENT_INFERRED,
                                                    List.of("msg"),
                                                    true))
                                    .status())
                    .isEqualTo(MemoryStatus.SUPPRESSED);
        }
    }

    @Test
    void deletePhysicallyErasesEvidenceAndOldRequestsNeverRevive() {
        var first = memory.execute(ui("add"), add("中文"));
        var removed =
                memory.execute(
                        ui("delete"),
                        new MemoryCommand.Remove(
                                first.memoryId(), 1, MemoryWriteReason.USER_REQUESTED, List.of()));
        assertThat(removed.status()).isEqualTo(MemoryStatus.REMOVED);
        assertThat(removed.targetExists()).isFalse();
        assertThat(removed.revision()).isEqualTo(2);
        assertThat(memory.snapshot().entries()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_evidence", Integer.class))
                .isZero();
        assertThat(suppressions.find(contentPolicy.hash("中文"))).isNotNull();
        assertThat(memory.execute(ui("add"), add("中文")).targetExists()).isFalse();
        assertThat(
                        memory.execute(
                                ui("delete"),
                                new MemoryCommand.Remove(
                                        first.memoryId(),
                                        1,
                                        MemoryWriteReason.USER_REQUESTED,
                                        List.of())))
                .isEqualTo(removed);
    }

    @Test
    void protectedRemovalRequiresExplicitCurrentUserEvidence() {
        var first = memory.execute(ui("add"), add("中文"));
        stubEvidence(userEvidence("msg", "execution", 11));
        assertThat(
                        memory.execute(
                                        main("auto", 1),
                                        new MemoryCommand.Remove(
                                                first.memoryId(),
                                                1,
                                                MemoryWriteReason.AGENT_INFERRED,
                                                List.of("msg")))
                                .status())
                .isEqualTo(MemoryStatus.PROTECTED);
        assertThat(
                        memory.execute(
                                        main("explicit", 1),
                                        new MemoryCommand.Remove(
                                                first.memoryId(),
                                                1,
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of("msg")))
                                .status())
                .isEqualTo(MemoryStatus.REMOVED);
    }

    @Test
    void restorationRequiresFreshEvidenceAndRevisionAndCreatesNewIdentity() {
        var first = memory.execute(ui("add"), add("中文"));
        var old = userEvidence("msg", "execution", 11);
        memory.execute(
                ui("delete"),
                new MemoryCommand.Remove(
                        first.memoryId(), 1, MemoryWriteReason.USER_REQUESTED, List.of()));
        var restore =
                new MemoryCommand.Add(
                        MemoryKind.COLLABORATION_PREFERENCE,
                        "中文",
                        MemoryWriteReason.USER_REQUESTED,
                        List.of("msg"),
                        true);
        stubEvidence(old);
        assertThat(memory.execute(main("stale", 1), restore).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(memory.execute(main("old-evidence", 2), restore).status())
                .isEqualTo(MemoryStatus.SUPPRESSED);
        stubEvidence(userEvidence("msg", "execution", 12));
        var restored = memory.execute(main("fresh", 2), restore);
        assertThat(restored.status()).isEqualTo(MemoryStatus.CREATED);
        assertThat(restored.memoryId()).isNotEqualTo(first.memoryId());
        assertThat(suppressions.find(contentPolicy.hash("中文"))).isNull();
        assertThat(
                        memory.execute(
                                        ui("old-id"),
                                        new MemoryCommand.Replace(
                                                first.memoryId(),
                                                1,
                                                "英文",
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of()))
                                .status())
                .isEqualTo(MemoryStatus.NOT_FOUND);
    }

    @Test
    void restoreAgainstSurvivingDuplicateConfirmsItWithoutRevokingSuppression() {
        stubEvidence(userEvidence("msg", "execution", 11));
        var first = memory.execute(main("profile", 0), inferred("中文", "msg"));
        var other =
                memory.execute(
                        main("preference", 1),
                        new MemoryCommand.Add(
                                MemoryKind.COLLABORATION_PREFERENCE,
                                "中文",
                                MemoryWriteReason.AGENT_INFERRED,
                                List.of("msg"),
                                false));
        memory.execute(
                ui("delete"),
                new MemoryCommand.Remove(
                        first.memoryId(), 1, MemoryWriteReason.USER_REQUESTED, List.of()));
        var result =
                memory.execute(
                        ui("restore-other"),
                        new MemoryCommand.Add(
                                MemoryKind.COLLABORATION_PREFERENCE,
                                "中文",
                                MemoryWriteReason.USER_REQUESTED,
                                List.of(),
                                true));
        assertThat(result.status()).isEqualTo(MemoryStatus.ALREADY_EXISTS);
        assertThat(result.created()).isFalse();
        assertThat(result.memoryId()).isEqualTo(other.memoryId());
        assertThat(entries.find(other.memoryId()).userProtected()).isTrue();
        assertThat(suppressions.find(contentPolicy.hash("中文"))).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_evidence", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void quotaFailureKeepsSuppressionAndUiCanRestoreAfterSpaceIsFreed() {
        var first = memory.execute(ui("add"), add("中文"));
        memory.execute(
                ui("delete"),
                new MemoryCommand.Remove(
                        first.memoryId(), 1, MemoryWriteReason.USER_REQUESTED, List.of()));
        var full = memory.execute(ui("full"), add("A".repeat(800)));
        var restore =
                new MemoryCommand.Add(
                        MemoryKind.PERSON_PROFILE,
                        "中文",
                        MemoryWriteReason.USER_REQUESTED,
                        List.of(),
                        true);
        assertThat(memory.execute(ui("no-room"), restore).status())
                .isEqualTo(MemoryStatus.QUOTA_EXCEEDED);
        assertThat(suppressions.find(contentPolicy.hash("中文"))).isNotNull();
        memory.execute(
                ui("free"),
                new MemoryCommand.Remove(
                        full.memoryId(), 1, MemoryWriteReason.USER_REQUESTED, List.of()));
        assertThat(memory.execute(ui("restore"), restore).status()).isEqualTo(MemoryStatus.CREATED);
    }

    @Test
    void backgroundConfirmOnlyRefreshesOnNewEvidence() {
        memory.initialize();
        var claim = claimFixture();
        stubEvidence(userEvidence("msg", "execution", 11));
        var first = memory.execute(background("add", 0, claim), inferred("中文", "msg"));
        var before = entries.find(first.memoryId());
        var confirm = new MemoryCommand.Confirm(first.memoryId(), 1, List.of("msg"));
        assertThat(memory.execute(background("same", 1, claim), confirm).status())
                .isEqualTo(MemoryStatus.CONFIRMED);
        assertThat(entries.find(first.memoryId())).isEqualTo(before);
        stubEvidence(userEvidence("new", "execution", 12));
        var fresh =
                memory.execute(
                        background("fresh", 1, claim),
                        new MemoryCommand.Confirm(first.memoryId(), 1, List.of("new")));
        assertThat(fresh.status()).isEqualTo(MemoryStatus.CONFIRMED);
        assertThat(fresh.revision()).isEqualTo(2);
        assertThat(entries.find(first.memoryId()).lastConfirmedAt())
                .isAfter(before.lastConfirmedAt());
        assertThat(
                        memory.execute(
                                        main("main-confirm", 2),
                                        new MemoryCommand.Confirm(
                                                first.memoryId(), 2, List.of("new")))
                                .status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
    }
}
