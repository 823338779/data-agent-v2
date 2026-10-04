package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import java.util.List;
import org.junit.jupiter.api.Test;

class HierarchicalLegacyCheckpointAdapterTest {
    private static final String ID = "id";
    private static final String JSON = "{}";
    private static final String HASH = "0".repeat(64);

    @Test
    void legacyMappingRequiresCompleteSources() {
        var checkpoint = new HierarchicalCheckpoint(ID, ID, ID, 7, 1, 1);
        var cold =
                new HierarchicalCold(
                        ID,
                        ID,
                        new EventSummaryCoverage(1, 1, 1, 2),
                        JSON,
                        2,
                        2,
                        100,
                        ID,
                        ID,
                        HASH);
        var userEnd = new ContextStepCursor(1, ContextStepCursor.PositionKind.MESSAGE_END, 0);
        var end = new ContextStepCursor(2, ContextStepCursor.PositionKind.MESSAGE_END, 0);
        var user = new ContextStepGroup(ContextStepCursor.ORIGIN, userEnd, List.of(), HASH, true);
        var group = new ContextStepGroup(userEnd, end, List.of(), HASH, true);
        var adapter = new HierarchicalLegacyCheckpointAdapter();
        assertThat(adapter.mapCoveredThrough(checkpoint, cold, List.of(user, group))).contains(end);
        assertThat(adapter.mapCoveredThrough(checkpoint, cold, List.of(user))).isEmpty();
        assertThat(
                        adapter.mapCoveredThrough(
                                checkpoint,
                                cold,
                                List.of(
                                        user,
                                        new ContextStepGroup(
                                                userEnd, end, List.of(), HASH, false))))
                .isEmpty();
    }
}
