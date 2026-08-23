package com.datagent.agentv2.context.hierarchical.domain;

import java.util.List;
import java.util.Objects;

/** 单 Cold 渐进压缩的一次冻结计划，不保存任何消息正文。 */
public record RollingColdCompactionPlan(
        long inputUpToMessageSequence,
        String sourceColdId,
        TurnRange targetCoverage,
        TurnRange rawSourceRange,
        TurnRange protectedRawRange,
        Long currentIncompleteTurn,
        int historyMemoryBudget,
        List<MessageBatch> batches) {

    public RollingColdCompactionPlan {
        if (inputUpToMessageSequence < 0 || historyMemoryBudget <= 0) {
            throw new IllegalArgumentException("Invalid rolling Cold plan budget or boundary");
        }
        targetCoverage = Objects.requireNonNull(targetCoverage, "targetCoverage");
        rawSourceRange = Objects.requireNonNull(rawSourceRange, "rawSourceRange");
        protectedRawRange = Objects.requireNonNull(protectedRawRange, "protectedRawRange");
        batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
    }

    public record TurnRange(long startTurn, long endTurn) {
        public static final TurnRange EMPTY = new TurnRange(0, 0);

        public TurnRange {
            boolean empty = startTurn == 0 && endTurn == 0;
            if (!empty && (startTurn <= 0 || endTurn < startTurn)) {
                throw new IllegalArgumentException("Invalid turn range");
            }
        }

        public boolean isEmpty() {
            return startTurn == 0;
        }
    }

    public record MessageBatch(
            long startTurn, long endTurn, long startMessageSequence, long endMessageSequence) {
        public MessageBatch {
            if (startTurn <= 0
                    || endTurn < startTurn
                    || startMessageSequence <= 0
                    || endMessageSequence < startMessageSequence) {
                throw new IllegalArgumentException("Invalid rolling Cold message batch");
            }
        }
    }
}
