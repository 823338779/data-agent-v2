package com.datagent.agentv2.datasource.query;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 单个 QueryTask 获得的物理表、列和 JOIN 白名单。 */
public record QueryAccessScope(
        Set<String> allowedTables,
        Map<String, Set<String>> allowedColumnsByTable,
        Set<AllowedJoin> allowedJoins,
        int maxRows) {

    public QueryAccessScope {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("查询最大行数必须大于 0");
        }
        allowedTables = normalizeSet(allowedTables);
        allowedColumnsByTable =
                allowedColumnsByTable.entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        entry -> normalize(entry.getKey()),
                                        entry -> normalizeSet(entry.getValue())));
        allowedJoins =
                allowedJoins.stream()
                        .map(AllowedJoin::normalized)
                        .collect(Collectors.toUnmodifiableSet());
    }

    public record AllowedJoin(
            String leftTable, String leftColumn, String rightTable, String rightColumn) {
        AllowedJoin normalized() {
            return new AllowedJoin(
                    normalize(leftTable),
                    normalize(leftColumn),
                    normalize(rightTable),
                    normalize(rightColumn));
        }

        boolean matches(
                String firstTable, String firstColumn, String secondTable, String secondColumn) {
            AllowedJoin candidate =
                    new AllowedJoin(firstTable, firstColumn, secondTable, secondColumn)
                            .normalized();
            return equals(candidate)
                    || (leftTable.equals(candidate.rightTable)
                            && leftColumn.equals(candidate.rightColumn)
                            && rightTable.equals(candidate.leftTable)
                            && rightColumn.equals(candidate.leftColumn));
        }
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeSet(Set<String> values) {
        return values.stream()
                .map(QueryAccessScope::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }
}
