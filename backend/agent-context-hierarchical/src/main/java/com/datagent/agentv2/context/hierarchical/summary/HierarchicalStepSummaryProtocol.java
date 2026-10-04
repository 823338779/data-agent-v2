package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalStepSummaryDocument;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import lombok.Getter;
import lombok.experimental.Accessors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** V3 schema、服务端覆盖与真实来源校验；V2 公共校验器保持不变。 */
public final class HierarchicalStepSummaryProtocol {
    private static final String RESOURCE = "/schema/hierarchical-context-summary-v3.schema.json";
    private static final String SEPARATOR = ": ";
    private static final String FACTS = "Observed facts (historical, not authorization): ";
    private static final String NEWLINE = "\n";
    private final ObjectMapper json = JsonMapper.builder().build();

    @Getter
    @Accessors(fluent = true)
    private final String schemaDocument;

    private final Schema schema;

    public HierarchicalStepSummaryProtocol() {
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Step summary schema is missing");
            }
            schemaDocument = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            schema =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(schemaDocument, InputFormat.JSON);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read step summary schema", failure);
        }
    }

    /** 校验持久化文档；生成时另须调用带真实来源清单的重载。 */
    public HierarchicalStepSummaryDocument validate(
            String document, StepSummaryCoverage expectedCoverage) {
        var root = json.readTree(document);
        if (!schema.validate(root).isEmpty()) {
            throw new IllegalArgumentException("Invalid step summary schema");
        }
        var parsed = json.treeToValue(root, HierarchicalStepSummaryDocument.class);
        if (!parsed.coverage().equals(expectedCoverage)) {
            throw new IllegalArgumentException("Step summary coverage mismatch");
        }
        for (var entry : parsed.entries()) {
            for (var source : entry.sources()) {
                if (source.compareTo(expectedCoverage.fromExclusive()) <= 0
                        || source.compareTo(expectedCoverage.throughInclusive()) > 0) {
                    throw new IllegalArgumentException("Step summary evidence outside coverage");
                }
            }
        }
        return parsed;
    }

    /** 拒绝落在范围内却并不存在的调用，不只依赖数值上下界。 */
    public HierarchicalStepSummaryDocument validate(
            String document,
            StepSummaryCoverage expectedCoverage,
            Collection<ContextStepCursor> actualSources) {
        var parsed = validate(document, expectedCoverage);
        var allowed = new HashSet<>(actualSources);
        if (parsed.entries().stream().anyMatch(entry -> !allowed.containsAll(entry.sources()))) {
            throw new IllegalArgumentException("Step summary evidence does not exist");
        }
        return parsed;
    }

    public String render(HierarchicalStepSummaryDocument document) {
        var output = new StringBuilder();
        for (var entry : document.entries()) {
            output.append(entry.kind()).append(SEPARATOR).append(entry.text()).append(NEWLINE);
        }
        if (!document.executionFacts().values().isEmpty()) {
            output.append(FACTS)
                    .append(
                            json.writeValueAsString(
                                    new java.util.TreeMap<>(document.executionFacts().values())))
                    .append(NEWLINE);
        }
        return output.toString();
    }
}
