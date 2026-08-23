package com.datagent.agentv2.context.common.summary;

import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument.Event;
import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument.SourceRange;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 对事件摘要执行 Schema、可信 coverage 和逐项来源范围校验。 */
public final class EventContextSummaryValidator {

    public static final int SCHEMA_VERSION = 2;

    private static final String SCHEMA_RESOURCE = "/schema/context-summary-v2.schema.json";
    private static final String INVALID_DOCUMENT = "Invalid event context summary document";
    private static final String MISSING_SCHEMA = "Event context summary schema resource is missing";

    private final ObjectMapper json;
    private final String schemaDocument;

    public EventContextSummaryValidator() {
        this(JsonMapper.builder().build());
    }

    EventContextSummaryValidator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
        this.schemaDocument = readSchema();
    }

    public String schemaDocument() {
        return schemaDocument;
    }

    public String schemaDocumentFor(EventSummaryCoverage coverage) {
        Objects.requireNonNull(coverage, "coverage");
        ObjectNode constrained = (ObjectNode) json.readTree(schemaDocument).deepCopy();
        JsonNode definitions = constrained.path("$defs");
        JsonNode coverageProperties = definitions.path("coverage").path("properties");
        putConst(coverageProperties, "startTurnSequence", coverage.startTurnSequence());
        putConst(coverageProperties, "endTurnSequence", coverage.endTurnSequence());
        putConst(coverageProperties, "startMessageSequence", coverage.startMessageSequence());
        putConst(coverageProperties, "endMessageSequence", coverage.endMessageSequence());
        JsonNode sourceProperties = definitions.path("sourceRange").path("properties");
        putRange(sourceProperties.path("startMessageSequence"), coverage);
        putRange(sourceProperties.path("endMessageSequence"), coverage);
        return json.writeValueAsString(constrained);
    }

    public EventContextSummaryDocument validate(
            String document, EventSummaryCoverage expectedCoverage) {
        try {
            String constrainedSchema = schemaDocumentFor(expectedCoverage);
            Schema schema =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(constrainedSchema, InputFormat.JSON);
            JsonNode root = json.readTree(Objects.requireNonNull(document, "document"));
            if (!schema.validate(root).isEmpty()) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }
            EventContextSummaryDocument parsed =
                    json.treeToValue(root, EventContextSummaryDocument.class);
            assertExactCoverage(parsed.coverage(), expectedCoverage);
            for (Event event : parsed.events()) {
                if (!event.hasSemanticEntry()) {
                    throw new IllegalArgumentException(INVALID_DOCUMENT);
                }
                sourceRanges(event).forEach(range -> assertWithin(range, expectedCoverage));
            }
            return parsed;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID_DOCUMENT, failure);
        }
    }

    private static Stream<SourceRange> sourceRanges(Event event) {
        return Stream.concat(
                Stream.concat(
                        event.title().sourceRanges().stream(),
                        event.status().sourceRanges().stream()),
                event.entries().flatMap(entry -> entry.sourceRanges().stream()));
    }

    private static void assertExactCoverage(
            EventContextSummaryDocument.Coverage actual, EventSummaryCoverage expected) {
        if (actual.startTurnSequence() != expected.startTurnSequence()
                || actual.endTurnSequence() != expected.endTurnSequence()
                || actual.startMessageSequence() != expected.startMessageSequence()
                || actual.endMessageSequence() != expected.endMessageSequence()) {
            throw new IllegalArgumentException(INVALID_DOCUMENT);
        }
    }

    private static void assertWithin(SourceRange range, EventSummaryCoverage coverage) {
        if (range.startMessageSequence() > range.endMessageSequence()
                || range.startMessageSequence() < coverage.startMessageSequence()
                || range.endMessageSequence() > coverage.endMessageSequence()) {
            throw new IllegalArgumentException(INVALID_DOCUMENT);
        }
    }

    private static void putConst(JsonNode properties, String field, long value) {
        ((ObjectNode) properties.path(field)).put("const", value);
    }

    private static void putRange(JsonNode property, EventSummaryCoverage coverage) {
        ObjectNode range = (ObjectNode) property;
        range.put("minimum", coverage.startMessageSequence());
        range.put("maximum", coverage.endMessageSequence());
    }

    private static String readSchema() {
        try (InputStream input =
                EventContextSummaryValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(MISSING_SCHEMA);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(MISSING_SCHEMA, failure);
        }
    }
}
