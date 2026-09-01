package com.datagent.agentv2.datasource.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/** 对本地不可变目录进行确定性召回，不依赖向量库或模型判断。 */
@RequiredArgsConstructor
public final class SemanticResourceSearchService {

    private final SemanticCatalog catalog;

    public SemanticResourceSet search(String question, int limit) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("语义检索问题不能为空");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("语义检索上限必须大于 0");
        }
        String normalized = normalize(question);
        List<Candidate> candidates = new ArrayList<>();
        for (SemanticDataset dataset : catalog.datasets()) {
            addCandidate(
                    candidates,
                    ResourceKind.DATASET,
                    dataset.id(),
                    null,
                    score(
                            normalized,
                            dataset.id(),
                            dataset.name(),
                            dataset.synonyms(),
                            dataset.description(),
                            dataset.table()));
            for (SemanticField field : dataset.fields()) {
                addCandidate(
                        candidates,
                        ResourceKind.FIELD,
                        field.id(),
                        dataset.id(),
                        score(
                                normalized,
                                field.id(),
                                field.name(),
                                field.synonyms(),
                                field.description(),
                                field.column()));
            }
        }
        for (SemanticMetric metric : catalog.metrics()) {
            addCandidate(
                    candidates,
                    ResourceKind.METRIC,
                    metric.id(),
                    metric.dataset(),
                    score(
                            normalized,
                            metric.id(),
                            metric.name(),
                            metric.synonyms(),
                            metric.description(),
                            null));
        }
        candidates.sort(
                Comparator.comparingInt(Candidate::score)
                        .reversed()
                        .thenComparing(candidate -> candidate.kind().name())
                        .thenComparing(Candidate::id));

        List<Candidate> selected = candidates.stream().limit(limit).toList();
        Set<String> datasetIds = new LinkedHashSet<>();
        Map<String, SemanticResourceSet.SemanticFieldRef> fields = new LinkedHashMap<>();
        Set<String> metricIds = new LinkedHashSet<>();
        for (Candidate candidate : selected) {
            if (candidate.datasetId() != null) {
                datasetIds.add(candidate.datasetId());
            }
            switch (candidate.kind()) {
                case DATASET -> datasetIds.add(candidate.id());
                case FIELD ->
                        fields.put(
                                candidate.datasetId() + ":" + candidate.id(),
                                new SemanticResourceSet.SemanticFieldRef(
                                        candidate.datasetId(),
                                        catalog.datasetsById()
                                                .get(candidate.datasetId())
                                                .fieldsById()
                                                .get(candidate.id())));
                case METRIC -> metricIds.add(candidate.id());
            }
        }
        List<SemanticRelationship> relationships =
                catalog.relationships().stream()
                        .filter(
                                rel ->
                                        datasetIds.contains(rel.leftDataset())
                                                && datasetIds.contains(rel.rightDataset()))
                        .toList();
        return new SemanticResourceSet(
                datasetIds.stream().map(catalog.datasetsById()::get).toList(),
                List.copyOf(fields.values()),
                metricIds.stream().map(catalog.metricsById()::get).toList(),
                relationships);
    }

    private static void addCandidate(
            List<Candidate> candidates, ResourceKind kind, String id, String datasetId, int score) {
        if (score > 0) {
            candidates.add(new Candidate(kind, id, datasetId, score));
        }
    }

    private static int score(
            String question,
            String id,
            String name,
            List<String> synonyms,
            String description,
            String physicalName) {
        if (equals(question, name)) return 100;
        if (containsExact(question, synonyms)) return 90;
        if (contains(question, name)) return 80;
        if (containsAny(question, synonyms)) return 70;
        if (contains(question, id) || contains(question, physicalName)) return 60;
        if (contains(question, description)) return 30;
        return 0;
    }

    private static boolean equals(String left, String right) {
        return right != null && left.equals(normalize(right));
    }

    private static boolean containsExact(String question, List<String> values) {
        return values != null
                && values.stream()
                        .map(SemanticResourceSearchService::normalize)
                        .anyMatch(question::equals);
    }

    private static boolean containsAny(String question, List<String> values) {
        return values != null && values.stream().anyMatch(value -> contains(question, value));
    }

    private static boolean contains(String question, String value) {
        return value != null && !value.isBlank() && question.contains(normalize(value));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private enum ResourceKind {
        DATASET,
        FIELD,
        METRIC
    }

    private record Candidate(ResourceKind kind, String id, String datasetId, int score) {}
}
