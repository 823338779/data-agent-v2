package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.config.MemoryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;

/** 只选择连续完整消息；预算不足不截断正文，也不越过未知或仍在运行的消息。 */
@RequiredArgsConstructor
public final class MemoryReviewSliceSelector {
    private final MemoryProperties properties;

    public Slice select(
            List<MemoryReviewMessage> messages,
            long fromSequence,
            long throughSequence,
            ToIntFunction<List<MemoryEvidence>> estimate,
            int maxInputTokens) {
        var selected = new ArrayList<MemoryEvidence>();
        long cursor = fromSequence;
        long chars = 0;
        for (var row : messages) {
            if (row.sequence() <= cursor) continue;
            if (row.sequence() > throughSequence
                    || row.sequence() != cursor + 1
                    || row.state() == MemoryReviewMessage.State.PENDING) break;
            if (row.state() == MemoryReviewMessage.State.EXCLUDED) {
                cursor = row.sequence();
                continue;
            }
            if (selected.size() >= properties.getReviewMessageLimit()) break;
            var candidate = new ArrayList<>(selected);
            candidate.add(row.evidence());
            if (chars + row.evidence().content().length() > properties.getReviewCharLimit()
                    || estimate.applyAsInt(List.copyOf(candidate)) > maxInputTokens)
                return new Slice(selected, cursor, selected.isEmpty());
            selected.add(row.evidence());
            chars += row.evidence().content().length();
            cursor = row.sequence();
        }
        return new Slice(selected, cursor, false);
    }

    /** reviewedThroughSequence 是已判断的连续包含上界；blocked 表示首条完整正文无法装入。 */
    public record Slice(
            List<MemoryEvidence> messages, long reviewedThroughSequence, boolean blocked) {
        public Slice {
            messages = List.copyOf(messages);
        }
    }
}
