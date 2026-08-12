package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.FrontierSelection;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarmSegmentFrontierSelectorTest {

    private static final String CHILD_ONE_ID = "child-1";
    private static final String CHILD_TWO_ID = "child-2";
    private static final String CHILD_THREE_ID = "child-3";
    private static final String PARENT_ID = "parent";
    private static final String GRANDPARENT_ID = "grandparent";
    private static final String OLD_ONE_ID = "old-1";
    private static final String OLD_TWO_ID = "old-2";
    private static final String OLD_PARENT_ID = "old-parent";
    private static final String RECENT_ID = "recent";

    private final WarmSegmentFrontierSelector selector = new WarmSegmentFrontierSelector();
    private final LsmInvariantValidator validator = new LsmInvariantValidator();

    @Test
    void frontierNeverContainsParentAndChild() {
        List<LsmSummarySegment> tree = treeWithParentAndChildren();

        FrontierSelection selection =
                selector.select(tree, new LsmSummarySegment.Coverage(1, 30), 800);

        assertThat(selection.segmentIds()).doesNotContain(PARENT_ID, CHILD_ONE_ID);
        validator.assertContinuous(selection.coverage());
        validator.assertNoParentChild(selection, tree);
    }

    @Test
    void usesCoarserOldHistoryBeforeRecentHistoryWhenBudgetIsTight() {
        List<LsmSummarySegment> segments =
                List.of(
                        segment(OLD_ONE_ID, 0, 1, 10, 300, List.of()),
                        segment(OLD_TWO_ID, 0, 11, 20, 300, List.of()),
                        segment(OLD_PARENT_ID, 1, 1, 20, 200, List.of(OLD_ONE_ID, OLD_TWO_ID)),
                        segment(RECENT_ID, 0, 21, 30, 300, List.of()));

        FrontierSelection selection =
                selector.select(segments, new LsmSummarySegment.Coverage(1, 30), 500);

        assertThat(selection.segmentIds()).containsExactly(OLD_PARENT_ID, RECENT_ID);
        assertThat(selection.estimatedTokenCount()).isEqualTo(500);
    }

    @Test
    void returnsTheSameOrderedFrontierForShuffledInput() {
        List<LsmSummarySegment> ordered = treeWithParentAndChildren();
        List<LsmSummarySegment> shuffled =
                List.of(
                        ordered.get(2),
                        ordered.get(4),
                        ordered.get(0),
                        ordered.get(3),
                        ordered.get(1));
        LsmSummarySegment.Coverage coverage = new LsmSummarySegment.Coverage(1, 30);

        assertThat(selector.select(shuffled, coverage, 800))
                .isEqualTo(selector.select(ordered, coverage, 800));
    }

    private static List<LsmSummarySegment> treeWithParentAndChildren() {
        return List.of(
                segment(CHILD_ONE_ID, 0, 1, 10, 500, List.of()),
                segment(CHILD_TWO_ID, 0, 11, 20, 500, List.of()),
                segment(PARENT_ID, 1, 1, 20, 250, List.of(CHILD_ONE_ID, CHILD_TWO_ID)),
                segment(CHILD_THREE_ID, 0, 21, 30, 500, List.of()),
                segment(GRANDPARENT_ID, 2, 1, 30, 400, List.of(PARENT_ID, CHILD_THREE_ID)));
    }

    private static LsmSummarySegment segment(
            String id, int level, long start, long end, int tokens, List<String> sources) {
        return new LsmSummarySegment(
                id,
                Kind.STANDARD,
                level,
                new LsmSummarySegment.Coverage(start, end),
                tokens,
                sources,
                false);
    }
}
