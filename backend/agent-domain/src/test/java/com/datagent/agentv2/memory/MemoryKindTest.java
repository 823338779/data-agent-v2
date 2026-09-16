package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryKindTest {

    @Test
    void quotaRepresentsInventoryAboveAValidReducedLimit() {
        var quota = new MemoryQuota(12, 5);
        assertThat(quota.used()).isEqualTo(12);
        assertThat(quota.limit()).isEqualTo(5);
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryQuota(-1, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryQuota(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryQuota(0, -1));
    }

    @Test
    void kindsUseOnlyTheTwoStableWireValues() {
        assertThat(MemoryKind.PERSON_PROFILE.value()).isEqualTo("person_profile");
        assertThat(MemoryKind.COLLABORATION_PREFERENCE.value())
                .isEqualTo("collaboration_preference");
        assertThat(MemoryKind.parse("person_profile")).isEqualTo(MemoryKind.PERSON_PROFILE);
        assertThat(MemoryKind.parse("collaboration_preference"))
                .isEqualTo(MemoryKind.COLLABORATION_PREFERENCE);
        assertThatIllegalArgumentException().isThrownBy(() -> MemoryKind.parse("work_context"));
    }

    @Test
    void snapshotsDefensivelyCopyEntriesAndQuotas() {
        var entries = new ArrayList<MemoryEntry>();
        var quotas = new EnumMap<MemoryKind, MemoryQuota>(MemoryKind.class);
        quotas.put(MemoryKind.PERSON_PROFILE, new MemoryQuota(0, 800));
        var state = new MemoryState(0, 0, true, true, true, 1, null);

        var snapshot = new MemorySnapshot(state, entries, quotas);
        entries.add(entry());
        quotas.clear();

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.quotas())
                .containsEntry(MemoryKind.PERSON_PROFILE, new MemoryQuota(0, 800));
        assertThatThrownBy(() -> snapshot.entries().add(entry()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandsDefensivelyCopyEvidenceIdentifiers() {
        var evidenceIds = new ArrayList<>(List.of("message-1"));
        var command =
                new MemoryCommand.Add(
                        MemoryKind.PERSON_PROFILE,
                        "用户偏好中文",
                        MemoryWriteReason.USER_REQUESTED,
                        evidenceIds,
                        false);

        evidenceIds.add("message-2");

        assertThat(command.evidenceMessageIds()).containsExactly("message-1");
        assertThatThrownBy(() -> command.evidenceMessageIds().add("message-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outcomeRecognizesExactlyTheFiveSuccessfulStatuses() {
        assertThat(
                        List.of(
                                        MemoryStatus.CREATED,
                                        MemoryStatus.ALREADY_EXISTS,
                                        MemoryStatus.UPDATED,
                                        MemoryStatus.REMOVED,
                                        MemoryStatus.CONFIRMED)
                                .stream()
                                .allMatch(
                                        status ->
                                                new MemoryOutcome(
                                                                status, null, null, 0, false, false,
                                                                Map.of())
                                                        .successful()))
                .isTrue();
        assertThat(
                        new MemoryOutcome(
                                        MemoryStatus.INVALID_REQUEST,
                                        null,
                                        null,
                                        0,
                                        false,
                                        false,
                                        Map.of())
                                .successful())
                .isFalse();
    }

    private static MemoryEntry entry() {
        var now = LocalDateTime.of(2026, 9, 16, 10, 0);
        return new MemoryEntry(
                "memory-1",
                MemoryKind.PERSON_PROFILE,
                "用户偏好中文",
                "hash",
                1,
                6,
                MemorySource.USER_DIRECT,
                true,
                1,
                now,
                now,
                now,
                "prompt-v1");
    }
}
