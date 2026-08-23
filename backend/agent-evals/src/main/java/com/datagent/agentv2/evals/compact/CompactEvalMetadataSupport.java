package com.datagent.agentv2.evals.compact;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 数据集标签归一化工具；所有方法只处理评测侧元数据。 */
final class CompactEvalMetadataSupport {

    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})");

    private CompactEvalMetadataSupport() {}

    static String positionPeriod(double relativePosition) {
        if (relativePosition < 1.0 / 3.0) {
            return "early";
        }
        return relativePosition < 2.0 / 3.0 ? "middle" : "recent";
    }

    static int positionSegment(double relativePosition) {
        double bounded = Math.max(0.0, Math.min(relativePosition, 1.0));
        return Math.min(6, (int) (bounded * 6) + 1);
    }

    static String normalizeDate(String value) {
        LocalDate parsed = parseDate(value);
        return parsed == null ? value : parsed.toString();
    }

    static Long memoryAgeDays(String questionDate, String evidenceDate) {
        LocalDate question = parseDate(questionDate);
        LocalDate evidence = parseDate(evidenceDate);
        return question == null || evidence == null
                ? null
                : ChronoUnit.DAYS.between(evidence, question);
    }

    static String earliest(Collection<String> dates) {
        return extremum(dates, Comparator.naturalOrder());
    }

    static String latest(Collection<String> dates) {
        return extremum(dates, Comparator.reverseOrder());
    }

    private static String extremum(Collection<String> dates, Comparator<LocalDate> comparator) {
        return dates.stream()
                .map(CompactEvalMetadataSupport::parseDate)
                .filter(Objects::nonNull)
                .min(comparator)
                .map(LocalDate::toString)
                .orElse(null);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException invalidDate) {
            return null;
        }
    }
}
