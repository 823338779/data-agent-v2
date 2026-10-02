package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalMemoryViewTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 10, 30);

    @Test
    void publicViewKeepsUiFieldsAndOmitsInternalStorageDetails() {
        var view =
                PersonalMemoryView.composeFromMemory(memory("memory-1", MemoryKind.PERSON_PROFILE));

        assertThat(view)
                .isEqualTo(
                        new PersonalMemoryView(
                                "memory-1",
                                MemoryKind.PERSON_PROFILE,
                                "用户主要使用中文",
                                MemorySource.USER_UI,
                                true,
                                3,
                                "2026-09-16T10:30:00+08:00"));
    }

    @Test
    void listUsesIndependentStableCursorsAndCompleteSnapshotQuotas() {
        var snapshot =
                new MemorySnapshot(
                        state(),
                        List.of(
                                memory("profile-2", MemoryKind.PERSON_PROFILE),
                                memory("preference-2", MemoryKind.COLLABORATION_PREFERENCE),
                                memory("profile-1", MemoryKind.PERSON_PROFILE),
                                memory("preference-1", MemoryKind.COLLABORATION_PREFERENCE)),
                        quotas());

        var view = PersonalMemoryListView.composeFromSnapshot(snapshot, null, null, 1);

        assertThat(view.groups().get(MemoryKind.PERSON_PROFILE).entries())
                .extracting(PersonalMemoryView::memoryId)
                .containsExactly("profile-1");
        assertThat(view.groups().get(MemoryKind.PERSON_PROFILE).nextCursor())
                .isEqualTo("profile-1");
        assertThat(view.groups().get(MemoryKind.COLLABORATION_PREFERENCE).entries())
                .extracting(PersonalMemoryView::memoryId)
                .containsExactly("preference-1");
        assertThat(view.groups().get(MemoryKind.COLLABORATION_PREFERENCE).nextCursor())
                .isEqualTo("preference-1");
        assertThat(view.quotas()).isEqualTo(quotas());
        assertThat(view.revision()).isEqualTo(7);
    }

    @Test
    void kindFilterLeavesUnrequestedGroupEmpty() {
        var snapshot =
                new MemorySnapshot(
                        state(),
                        List.of(
                                memory("profile-1", MemoryKind.PERSON_PROFILE),
                                memory("preference-1", MemoryKind.COLLABORATION_PREFERENCE)),
                        quotas());

        var view =
                PersonalMemoryListView.composeFromSnapshot(
                        snapshot, MemoryKind.PERSON_PROFILE, null, 20);

        assertThat(view.groups().get(MemoryKind.PERSON_PROFILE).entries()).hasSize(1);
        assertThat(view.groups().get(MemoryKind.COLLABORATION_PREFERENCE).entries()).isEmpty();
        assertThat(view.groups().get(MemoryKind.COLLABORATION_PREFERENCE).nextCursor()).isNull();
    }

    private static MemoryEntry memory(String id, MemoryKind kind) {
        return new MemoryEntry(
                id,
                kind,
                "用户主要使用中文",
                "internal-hash",
                1,
                8,
                MemorySource.USER_UI,
                true,
                3,
                NOW.minusDays(1),
                NOW,
                NOW,
                "internal-prompt-version");
    }

    private static MemoryState state() {
        return new MemoryState(7, 2, true, true, true, 1, NOW.minusDays(2));
    }

    private static Map<MemoryKind, MemoryQuota> quotas() {
        return Map.of(
                MemoryKind.PERSON_PROFILE,
                new MemoryQuota(8, 800),
                MemoryKind.COLLABORATION_PREFERENCE,
                new MemoryQuota(8, 1500));
    }
}
