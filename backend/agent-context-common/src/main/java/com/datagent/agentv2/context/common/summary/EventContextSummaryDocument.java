package com.datagent.agentv2.context.common.summary;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 完整持久化、可按消息来源审计的事件摘要文档。 */
public record EventContextSummaryDocument(
        int schemaVersion, Coverage coverage, List<Event> events) {

    public EventContextSummaryDocument {
        coverage = Objects.requireNonNull(coverage, "coverage");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public record Coverage(
            long startTurnSequence,
            long endTurnSequence,
            long startMessageSequence,
            long endMessageSequence) {}

    public record Event(
            SourcedText title,
            SourcedStatus status,
            List<Entry> confirmedFacts,
            List<Entry> userPreferences,
            List<Entry> workingHypotheses,
            List<Entry> decisions,
            List<Entry> rejectedApproaches,
            List<Entry> toolFindings,
            List<Entry> openQuestions,
            List<Entry> nextSteps) {

        public Event {
            title = Objects.requireNonNull(title, "title");
            status = Objects.requireNonNull(status, "status");
            confirmedFacts = immutable(confirmedFacts);
            userPreferences = immutable(userPreferences);
            workingHypotheses = immutable(workingHypotheses);
            decisions = immutable(decisions);
            rejectedApproaches = immutable(rejectedApproaches);
            toolFindings = immutable(toolFindings);
            openQuestions = immutable(openQuestions);
            nextSteps = immutable(nextSteps);
        }

        Stream<Entry> entries() {
            return Stream.of(
                            confirmedFacts,
                            userPreferences,
                            workingHypotheses,
                            decisions,
                            rejectedApproaches,
                            toolFindings,
                            openQuestions,
                            nextSteps)
                    .flatMap(List::stream);
        }

        boolean hasSemanticEntry() {
            return entries().findAny().isPresent();
        }
    }

    public record SourcedText(String text, List<SourceRange> sourceRanges) {
        public SourcedText {
            text = Objects.requireNonNull(text, "text");
            sourceRanges = immutable(sourceRanges);
        }
    }

    public record SourcedStatus(Status value, List<SourceRange> sourceRanges) {
        public SourcedStatus {
            value = Objects.requireNonNull(value, "value");
            sourceRanges = immutable(sourceRanges);
        }
    }

    public record Entry(String statement, List<SourceRange> sourceRanges) {
        public Entry {
            statement = Objects.requireNonNull(statement, "statement");
            sourceRanges = immutable(sourceRanges);
        }
    }

    public record SourceRange(long startMessageSequence, long endMessageSequence) {}

    public enum Status {
        ACTIVE,
        COMPLETED,
        BLOCKED,
        UNKNOWN
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
