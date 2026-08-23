package com.datagent.agentv2.context.common.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventContextSummaryValidatorTest {

    private static final EventSummaryCoverage COVERAGE = new EventSummaryCoverage(1, 2, 10, 20);

    private final EventContextSummaryValidator validator = new EventContextSummaryValidator();

    @Test
    void acceptsMinimalValidEventDocument() {
        assertThat(validator.validate(validDocument(), COVERAGE).schemaVersion()).isEqualTo(2);
    }

    @Test
    void rejectsWrongSchemaVersionEmptyEventsAndWrongCoverage() {
        assertInvalid(validDocument().replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"));
        assertInvalid(validDocument().replaceFirst("\"events\": \\[.*", "\"events\": []}"));

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        validDocument(), new EventSummaryCoverage(1, 3, 10, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid event context summary document");
    }

    @Test
    void rejectsMissingProvenanceEmptySemanticsAndOutOfCoverageSource() {
        assertInvalid(
                validDocument()
                        .replaceFirst(
                                "\"sourceRanges\": \\[", "\"sourceRanges\": [] , \"ignored\": ["));
        assertInvalid(
                validDocument()
                        .replace(
                                "\"confirmedFacts\": [{",
                                "\"confirmedFacts\": [], \"discarded\": [{"));
        assertInvalid(
                validDocument()
                        .replace("\"startMessageSequence\": 11", "\"startMessageSequence\": 9"));
    }

    private void assertInvalid(String document) {
        assertThatThrownBy(() -> validator.validate(document, COVERAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid event context summary document");
    }

    static String validDocument() {
        return """
                {
                  "schemaVersion": 2,
                  "coverage": {
                    "startTurnSequence": 1,
                    "endTurnSequence": 2,
                    "startMessageSequence": 10,
                    "endMessageSequence": 20
                  },
                  "events": [{
                    "title": {"text": "订单统计", "sourceRanges": [{
                      "startMessageSequence": 10, "endMessageSequence": 10
                    }]},
                    "status": {"value": "ACTIVE", "sourceRanges": [{
                      "startMessageSequence": 20, "endMessageSequence": 20
                    }]},
                    "confirmedFacts": [{"statement": "范围已确认", "sourceRanges": [{
                      "startMessageSequence": 11, "endMessageSequence": 12
                    }] }],
                    "userPreferences": [], "workingHypotheses": [], "decisions": [],
                    "rejectedApproaches": [], "toolFindings": [], "openQuestions": [],
                    "nextSteps": []
                  }]
                }
                """;
    }
}
