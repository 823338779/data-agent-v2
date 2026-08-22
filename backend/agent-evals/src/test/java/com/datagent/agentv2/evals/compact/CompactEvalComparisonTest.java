package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompactEvalComparisonTest {

    @Test
    void alignsProbeAnswersAndBuildsIndependentSummaries() {
        CompactEvalRunArtifact left = artifact("lsm", true);
        CompactEvalRunArtifact right = artifact("hierarchical", false);

        CompactEvalComparison.ComparisonArtifact comparison =
                new CompactEvalComparison().compare(left, right);

        assertEquals(1, comparison.left().referenceMatches());
        assertEquals(0, comparison.right().referenceMatches());
        assertEquals("left answer", comparison.probes().getFirst().leftAnswer());
        assertEquals("right answer", comparison.probes().getFirst().rightAnswer());
    }

    @Test
    void rejectsArtifactsWithDifferentTurnInputs() {
        CompactEvalRunArtifact left = artifact("lsm", true);
        CompactEvalRunArtifact right = artifact("hierarchical", false);
        CompactEvalRunArtifact.TurnResult changed =
                new CompactEvalRunArtifact.TurnResult(
                        0, "probe", "different", "answer", false, "e", "done", "x", 2, 0, 0, 0, 1);
        right =
                new CompactEvalRunArtifact(
                        right.schemaVersion(),
                        right.mechanism(),
                        right.corpusSource(),
                        right.startedAt(),
                        right.durationMillis(),
                        List.of(
                                new CompactEvalRunArtifact.ScenarioResult(
                                        "scenario", "session", true, null, List.of(changed))));

        CompactEvalRunArtifact finalRight = right;
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompactEvalComparison().compare(left, finalRight));
    }

    @Test
    void keepsProbeWhenOneMechanismStopsBeforeIt() {
        CompactEvalRunArtifact left = artifact("lsm", true);
        CompactEvalRunArtifact right = artifact("hierarchical", false);
        right =
                new CompactEvalRunArtifact(
                        right.schemaVersion(),
                        right.mechanism(),
                        right.corpusSource(),
                        right.startedAt(),
                        right.durationMillis(),
                        List.of(
                                new CompactEvalRunArtifact.ScenarioResult(
                                        "scenario",
                                        "session",
                                        false,
                                        "service failure",
                                        List.of())));

        CompactEvalComparison.ComparisonArtifact comparison =
                new CompactEvalComparison().compare(left, right);

        assertEquals(1, comparison.probes().size());
        assertEquals(null, comparison.probes().getFirst().rightAnswer());
    }

    private CompactEvalRunArtifact artifact(String mechanism, boolean matched) {
        CompactEvalRunArtifact.TurnResult probe =
                new CompactEvalRunArtifact.TurnResult(
                        0,
                        "probe",
                        "question",
                        "answer",
                        matched,
                        "execution",
                        "done",
                        matched ? "left answer" : "right answer",
                        2,
                        1,
                        1,
                        0,
                        1L);
        return new CompactEvalRunArtifact(
                "compact-eval-run/v1",
                mechanism,
                "corpus",
                "2026-08-15T00:00:00Z",
                1L,
                List.of(
                        new CompactEvalRunArtifact.ScenarioResult(
                                "scenario", "session", true, null, List.of(probe))));
    }
}
