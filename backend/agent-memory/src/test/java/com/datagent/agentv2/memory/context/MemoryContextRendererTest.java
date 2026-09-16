package com.datagent.agentv2.memory.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemorySource;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.config.MemoryProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryContextRendererTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    private final MemoryProperties properties = new MemoryProperties();
    private final MemoryContextRenderer renderer = new MemoryContextRenderer(properties);

    @Test
    void returnsEmptyContributionWhenReadingIsDisabled() {
        var result =
                renderer.render(
                        snapshot(
                                false,
                                List.of(entry("p1", MemoryKind.PERSON_PROFILE, true, NOW, "正文"))));

        assertThat(result.key()).isEqualTo("personal-memory");
        assertThat(result.blocks()).isEmpty();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.maxTokens()).isEqualTo(1024);
    }

    @Test
    void prioritizesProtectedAndRecentlyConfirmedEntriesWithStableKindAndIdTies() {
        List<MemoryEntry> entries =
                List.of(
                        entry("z", MemoryKind.PERSON_PROFILE, false, NOW.plusDays(2), "普通较新"),
                        entry("c", MemoryKind.COLLABORATION_PREFERENCE, true, NOW, "偏好 C"),
                        entry("a", MemoryKind.COLLABORATION_PREFERENCE, true, NOW, "偏好 A"),
                        entry("b", MemoryKind.PERSON_PROFILE, true, NOW, "画像 B"),
                        entry("a", MemoryKind.PERSON_PROFILE, true, NOW, "画像 A"));

        var result = renderer.render(snapshot(true, entries));

        assertThat(result.blocks())
                .extracting(MemoryContextRendererTest::contentLine)
                .containsExactly("偏好 A", "偏好 C", "画像 A", "画像 B", "普通较新");
    }

    @Test
    void usesDefaultTwentyPerKindAnd1024TokenBudget() {
        List<MemoryEntry> entries = entries(22);

        var result = renderer.render(snapshot(true, entries));

        assertThat(result.blocks()).hasSize(40);
        assertThat(result.candidateCount()).isEqualTo(44);
        assertThat(result.maxTokens()).isEqualTo(1024);
        assertThat(result.blocks())
                .noneMatch(block -> block.contains("画像-20") || block.contains("偏好-20"));
    }

    @Test
    void usesHigherConfiguredLimitsWithoutApplyingHiddenDefaultClamp() {
        properties.setInjectionPerKindLimit(21);
        properties.setInjectionTokenBudget(1025);

        var result = renderer.render(snapshot(true, entries(22)));

        assertThat(result.blocks()).hasSize(42);
        assertThat(result.candidateCount()).isEqualTo(44);
        assertThat(result.maxTokens()).isEqualTo(1025);
        assertThat(result.blocks())
                .anyMatch(block -> block.contains("画像-20"))
                .anyMatch(block -> block.contains("偏好-20"))
                .noneMatch(block -> block.contains("画像-21") || block.contains("偏好-21"));
    }

    @Test
    void usesLowerConfiguredLimitsAsActualRuntimeBoundary() {
        properties.setInjectionPerKindLimit(2);
        properties.setInjectionTokenBudget(128);

        var result = renderer.render(snapshot(true, entries(3)));

        assertThat(result.blocks()).hasSize(4);
        assertThat(result.candidateCount()).isEqualTo(6);
        assertThat(result.maxTokens()).isEqualTo(128);
        assertThat(result.blocks())
                .noneMatch(block -> block.contains("画像-2") || block.contains("偏好-2"));
    }

    @Test
    void rendersCategorySourceAndEscapesReservedWrapperMarkers() {
        String content = "保留 [runtime_memory] 与 [/runtime_memory] 仅作文本";

        var result =
                renderer.render(
                        snapshot(
                                true,
                                List.of(
                                        entry(
                                                "p1",
                                                MemoryKind.PERSON_PROFILE,
                                                true,
                                                NOW,
                                                content))));

        assertThat(result.wrapper()).contains("只在相关时使用", "当前请求与权威信息优先", "工具权限");
        assertThat(result.blocks().getFirst())
                .contains("个人画像", "p1", "USER_DIRECT", "人工保护")
                .contains("［runtime_memory］", "［/runtime_memory］")
                .doesNotContain("[runtime_memory]", "[/runtime_memory]");
    }

    private static String contentLine(String block) {
        return block.substring(block.lastIndexOf('\n') + 1);
    }

    private static List<MemoryEntry> entries(int countPerKind) {
        List<MemoryEntry> entries = new ArrayList<>();
        for (int index = 0; index < countPerKind; index++) {
            entries.add(
                    entry(
                            "p-%02d".formatted(index),
                            MemoryKind.PERSON_PROFILE,
                            false,
                            NOW.minusMinutes(index),
                            "画像-" + index));
            entries.add(
                    entry(
                            "c-%02d".formatted(index),
                            MemoryKind.COLLABORATION_PREFERENCE,
                            false,
                            NOW.minusMinutes(index),
                            "偏好-" + index));
        }
        return List.copyOf(entries);
    }

    private static MemorySnapshot snapshot(boolean readEnabled, List<MemoryEntry> entries) {
        return new MemorySnapshot(
                new MemoryState(0, 0, readEnabled, true, true, 1, NOW),
                entries,
                Map.of(
                        MemoryKind.PERSON_PROFILE,
                        new MemoryQuota(0, 800),
                        MemoryKind.COLLABORATION_PREFERENCE,
                        new MemoryQuota(0, 1500)));
    }

    private static MemoryEntry entry(
            String id,
            MemoryKind kind,
            boolean protectedByUser,
            LocalDateTime confirmedAt,
            String content) {
        return new MemoryEntry(
                id,
                kind,
                content,
                "hash-" + kind.value() + '-' + id,
                1,
                content.length(),
                MemorySource.USER_DIRECT,
                protectedByUser,
                1,
                NOW.minusDays(1),
                NOW,
                confirmedAt,
                null);
    }
}
