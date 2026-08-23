package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan.MessageBatch;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan.TurnRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 保留最近十个完整轮次并把更老前缀顺序滚入单一 Cold。 */
public final class RollingColdCompactionPlanner {
    private static final int PROTECTED_COMPLETED_TURNS = 10;
    private final ConversationTokenEstimator estimator;

    public RollingColdCompactionPlanner(ConversationTokenEstimator estimator) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public RollingColdCompactionPlan plan(
            long inputUpToMessageSequence,
            HierarchicalCold sourceCold,
            List<ConversationTurn> turns,
            int historyMemoryBudget,
            int summaryInputHardTokenBudget) {
        List<ConversationTurn> frozen = List.copyOf(turns);
        List<ConversationTurn> completed =
                frozen.stream().filter(ConversationTurn::completed).toList();
        ConversationTurn current =
                frozen.stream().filter(turn -> !turn.completed()).reduce((a, b) -> b).orElse(null);
        int protectedStartIndex = Math.max(0, completed.size() - PROTECTED_COMPLETED_TURNS);
        List<ConversationTurn> protectedTurns =
                completed.subList(protectedStartIndex, completed.size());
        long protectedStart = protectedTurns.isEmpty() ? 0 : protectedTurns.getFirst().ordinal();
        long protectedEnd = protectedTurns.isEmpty() ? 0 : protectedTurns.getLast().ordinal();
        TurnRange protectedRange =
                protectedTurns.isEmpty()
                        ? TurnRange.EMPTY
                        : new TurnRange(protectedStart, protectedEnd);

        long sourceEnd = sourceCold == null ? 0 : sourceCold.coverage().endTurnSequence();
        long compressibleEnd = protectedStart == 0 ? 0 : protectedStart - 1;
        long rawStart = sourceEnd + 1;
        TurnRange rawRange =
                rawStart <= compressibleEnd
                        ? new TurnRange(rawStart, compressibleEnd)
                        : TurnRange.EMPTY;
        TurnRange target =
                compressibleEnd > 0 ? new TurnRange(1, compressibleEnd) : TurnRange.EMPTY;
        List<ConversationTurn> rawTurns =
                rawRange.isEmpty()
                        ? List.of()
                        : completed.stream()
                                .filter(turn -> turn.ordinal() >= rawRange.startTurn())
                                .filter(turn -> turn.ordinal() <= rawRange.endTurn())
                                .toList();
        List<MessageBatch> batches = batches(rawTurns, summaryInputHardTokenBudget);
        return new RollingColdCompactionPlan(
                inputUpToMessageSequence,
                sourceCold == null ? null : sourceCold.coldId(),
                target,
                rawRange,
                protectedRange,
                current == null ? null : current.ordinal(),
                historyMemoryBudget,
                batches);
    }

    private List<MessageBatch> batches(List<ConversationTurn> turns, int hardBudget) {
        if (hardBudget <= 0) {
            throw new IllegalArgumentException("summaryInputHardTokenBudget must be positive");
        }
        List<MessageBatch> result = new ArrayList<>();
        int start = 0;
        while (start < turns.size()) {
            int end = start;
            int tokens = 0;
            while (end < turns.size()) {
                int next = estimator.estimateMessages(turns.get(end).messages());
                if (end > start && tokens + next > hardBudget) {
                    break;
                }
                if (next > hardBudget) {
                    throw new IllegalStateException(
                            "A single conversation turn exceeds the summary input budget");
                }
                tokens += next;
                end++;
            }
            ConversationTurn first = turns.get(start);
            ConversationTurn last = turns.get(end - 1);
            result.add(
                    new MessageBatch(
                            first.ordinal(),
                            last.ordinal(),
                            first.startMessageSequence(),
                            last.endMessageSequence()));
            start = end;
        }
        return List.copyOf(result);
    }
}
