package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import java.util.List;
import java.util.Optional;

/** 仅把可从真实完整来源证明的 V2 Cold 覆盖映射为 V3；evaluated 不是 covered。 */
public final class HierarchicalLegacyCheckpointAdapter {
    public Optional<ContextStepCursor> mapCoveredThrough(
            HierarchicalCheckpoint legacy, HierarchicalCold cold, List<ContextStepGroup> source) {
        if (cold == null
                || !cold.coldId().equals(legacy.activeColdId())
                || !cold.conversationSessionId().equals(legacy.conversationSessionId())) {
            return Optional.empty();
        }
        var target =
                new ContextStepCursor(
                        cold.coverage().endMessageSequence(),
                        ContextStepCursor.PositionKind.MESSAGE_END,
                        0);
        var previous = ContextStepCursor.ORIGIN;
        for (var group : source) {
            if (!group.complete() || !group.fromExclusive().equals(previous)) {
                return Optional.empty();
            }
            previous = group.throughInclusive();
            if (previous.equals(target)) {
                return Optional.of(target);
            }
            if (previous.compareTo(target) > 0) {
                break;
            }
        }
        return Optional.empty();
    }
}
