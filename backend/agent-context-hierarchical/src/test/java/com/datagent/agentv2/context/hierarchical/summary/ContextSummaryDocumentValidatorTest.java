package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class ContextSummaryDocumentValidatorTest {

    private static final int NODE_TOKEN_LIMIT = 1_024;
    private static final int VALID_TOKEN_COUNT = 256;
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @Mock private ConversationTokenEstimator estimator;

    private ContextSummaryDocumentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ContextSummaryDocumentValidator(estimator);
        lenient().when(estimator.estimateSummary(anyString())).thenReturn(VALID_TOKEN_COUNT);
    }

    @Test
    void acceptsTheSevenRequiredStructuredFields() {
        ContextSummaryDocumentValidator.ValidationResult result =
                validator.validate(validSummary(), NODE_TOKEN_LIMIT, expectedRange());

        assertThat(result.document().schemaVersion()).isEqualTo(1);
        assertThat(result.document().confirmedFacts()).hasSize(1);
        assertThat(result.estimatedTokenCount()).isEqualTo(VALID_TOKEN_COUNT);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "confirmedFacts",
                "workingHypotheses",
                "decisions",
                "rejectedApproaches",
                "toolFindings",
                "nextSteps",
                "openQuestions"
            })
    void requiresEveryStructuredField(String field) throws Exception {
        ObjectNode missing = (ObjectNode) JSON.readTree(validSummary());
        missing.remove(field);

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        JSON.writeValueAsString(missing), NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "confirmedFacts",
                "workingHypotheses",
                "decisions",
                "rejectedApproaches",
                "toolFindings",
                "nextSteps",
                "openQuestions"
            })
    void requiresEveryStructuredFieldToBeAnArray(String field) throws Exception {
        ObjectNode wrongType = (ObjectNode) JSON.readTree(validSummary());
        wrongType.set(field, JSON.createObjectNode());

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        JSON.writeValueAsString(wrongType), NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    @Test
    void rejectsUnknownSchemaVersionAndUnexpectedFields() {
        String unknownVersion =
                validSummary().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        String unexpected = validSummary().replace("\n}", ",\n  \"rawReasoning\": \"hidden\"\n}");

        assertThatThrownBy(() -> validator.validate(unknownVersion, NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
        assertThatThrownBy(() -> validator.validate(unexpected, NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    @Test
    void rejectsOversizedStatementsAndFieldEntryCounts() {
        String oversizedStatement =
                validSummary().replace("metric m1 uses unit ms", "x".repeat(2_049));
        String entries =
                IntStream.range(0, 129)
                        .mapToObj(
                                index ->
                                        "{\"statement\":\"fact-%d\",\"sourceRanges\":[{\"startMessageSequence\":10,\"endMessageSequence\":10}]}"
                                                .formatted(index))
                        .collect(Collectors.joining(","));
        String oversizedField =
                validSummary()
                        .replaceFirst(
                                "(?s)\"confirmedFacts\": \\[.*?],",
                                "\"confirmedFacts\": [" + entries + "],");

        assertThatThrownBy(() -> validator.validate(oversizedStatement, NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
        assertThatThrownBy(() -> validator.validate(oversizedField, NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    @Test
    void rejectsLocalSummaryEstimateAboveConfiguredNodeLimit() {
        when(estimator.estimateSummary(anyString())).thenReturn(NODE_TOKEN_LIMIT + 1);

        assertThatThrownBy(() -> validator.validate(validSummary(), NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    @Test
    void rejectsCoverageOrEntrySourcesOutsideThePlannedRange() {
        String wrongCoverage = validSummary().replace("\"endTurn\": 2", "\"endTurn\": 3");
        String outsideSource =
                validSummary()
                        .replace(
                                "\"startMessageSequence\": 10, \"endMessageSequence\": 10",
                                "\"startMessageSequence\": 9, \"endMessageSequence\": 10");
        String invertedSource =
                validSummary()
                        .replace(
                                "\"startMessageSequence\": 10, \"endMessageSequence\": 10",
                                "\"startMessageSequence\": 11, \"endMessageSequence\": 10");

        assertThatThrownBy(
                        () -> validator.validate(wrongCoverage, NODE_TOKEN_LIMIT, expectedRange()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
        assertThatThrownBy(
                        () -> validator.validate(outsideSource, NODE_TOKEN_LIMIT, expectedRange()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
        assertThatThrownBy(() -> validator.validate(invertedSource, NODE_TOKEN_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid hierarchical context summary document");
    }

    private static HierarchicalSummaryGenerator.GenerationRange expectedRange() {
        return new HierarchicalSummaryGenerator.GenerationRange(1, 2, 10, 20, 1);
    }

    private static String validSummary() {
        return """
                {
                  "schemaVersion": 1,
                  "coverage": {
                    "startTurn": 1,
                    "endTurn": 2,
                    "startMessageSequence": 10,
                    "endMessageSequence": 20
                  },
                  "confirmedFacts": [{
                    "statement": "metric m1 uses unit ms",
                    "sourceRanges": [{"startMessageSequence": 10, "endMessageSequence": 10}]
                  }],
                  "workingHypotheses": [],
                  "decisions": [],
                  "rejectedApproaches": [],
                  "toolFindings": [],
                  "nextSteps": [],
                  "openQuestions": []
                }
                """;
    }
}
