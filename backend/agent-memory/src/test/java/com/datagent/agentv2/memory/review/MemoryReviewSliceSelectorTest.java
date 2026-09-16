package com.datagent.agentv2.memory.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.config.MemoryProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class MemoryReviewSliceSelectorTest {
    private final MemoryReviewSliceSelector selector =
            new MemoryReviewSliceSelector(new MemoryProperties());

    @Test
    void fortyFirstMessageRemainsForNextBatch() {
        var rows = LongStream.rangeClosed(1, 41).mapToObj(i -> row(i, "正文")).toList();
        var first = selector.select(rows, 0, 41, e -> e.size(), 100);
        assertThat(first.messages()).hasSize(40);
        assertThat(first.reviewedThroughSequence()).isEqualTo(40);
        assertThat(selector.select(rows.subList(40, 41), 40, 41, e -> e.size(), 100).messages())
                .hasSize(1);
    }

    @Test
    void anOversizedFirstMessageCannotAdvanceTheCursor() {
        var slice =
                selector.select(
                        List.of(row(7, "内容".repeat(100))),
                        6,
                        7,
                        rows -> rows.stream().mapToInt(m -> m.content().length()).sum() + 20,
                        100);
        assertThat(slice.blocked()).isTrue();
        assertThat(slice.reviewedThroughSequence()).isEqualTo(6);
    }

    @Test
    void pendingStopsWhileExcludedCanAdvance() {
        var slice =
                selector.select(
                        List.of(
                                new MemoryReviewMessage(
                                        1, MemoryReviewMessage.State.EXCLUDED, null),
                                row(2, "a"),
                                new MemoryReviewMessage(3, MemoryReviewMessage.State.PENDING, null),
                                row(4, "b")),
                        0,
                        4,
                        rows -> rows.size(),
                        100);
        assertThat(slice.reviewedThroughSequence()).isEqualTo(2);
        assertThat(slice.messages()).extracting(MemoryEvidence::sequence).containsExactly(2L);
    }

    @Test
    void cannotJumpUnclassifiedGapOrTruncateMessage() {
        assertThat(
                        selector.select(List.of(row(2, "x")), 0, 2, rows -> 1, 10)
                                .reviewedThroughSequence())
                .isZero();
        var slice =
                selector.select(
                        List.of(row(1, "a"), row(2, "bb")),
                        0,
                        2,
                        rows -> rows.stream().mapToInt(m -> m.content().length()).sum(),
                        2);
        assertThat(slice.reviewedThroughSequence()).isEqualTo(1);
        assertThat(slice.blocked()).isFalse();
    }

    static MemoryReviewMessage row(long sequence, String content) {
        return new MemoryReviewMessage(
                sequence,
                MemoryReviewMessage.State.ELIGIBLE,
                new MemoryEvidence(
                        "u" + sequence,
                        MemoryEvidence.Kind.USER_MESSAGE,
                        "s",
                        "e",
                        sequence,
                        "user_admin",
                        1,
                        content,
                        LocalDateTime.of(2026, 9, 17, 12, 0)));
    }
}
