package com.datagent.agentv2.context.lsm.summary;

import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 对 provider 摘要执行 JSON Schema、精确覆盖范围和来源范围校验。 */
public final class ContextSummaryDocumentValidator {

    /** 当前可持久化摘要正文的 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final String SCHEMA_RESOURCE = "/schema/context-summary-v1.schema.json";
    private static final String INVALID_DOCUMENT = "Invalid context summary document";
    private static final String MISSING_SCHEMA = "Context summary schema resource is missing";

    private final ObjectMapper json;
    private final String schemaDocument;
    private final Schema schema;

    /** 从插件自有资源加载并编译固定 v1 schema。 */
    public ContextSummaryDocumentValidator() {
        this(JsonMapper.builder().build());
    }

    ContextSummaryDocumentValidator(ObjectMapper json) {
        this.json = json;
        this.schemaDocument = readSchema();
        this.schema =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schemaDocument, InputFormat.JSON);
    }

    /** 返回发送给 provider 的原始固定 schema。 */
    public String schemaDocument() {
        return schemaDocument;
    }

    /** 校验并转换摘要，任何失败只返回稳定无正文异常。 */
    public ContextSummaryDocument validate(
            String document,
            LsmSummarySegment.Coverage expectedTurnCoverage,
            ContextSummaryGenerator.MessageCoverage expectedMessageCoverage) {
        try {
            JsonNode node = json.readTree(Objects.requireNonNull(document, "document"));
            if (!schema.validate(node).isEmpty()) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }
            ContextSummaryDocument summary = json.treeToValue(node, ContextSummaryDocument.class);
            validateCoverage(summary.coverage(), expectedTurnCoverage, expectedMessageCoverage);
            validateEntries(summary.entries(), expectedMessageCoverage);
            return summary;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID_DOCUMENT, failure);
        }
    }

    private static void validateCoverage(
            ContextSummaryDocument.Coverage actual,
            LsmSummarySegment.Coverage expectedTurns,
            ContextSummaryGenerator.MessageCoverage expectedMessages) {
        ContextSummaryDocument.Coverage expected =
                new ContextSummaryDocument.Coverage(
                        expectedTurns.startTurnSequence(),
                        expectedTurns.endTurnSequence(),
                        expectedMessages.startMessageSequence(),
                        expectedMessages.endMessageSequence());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(INVALID_DOCUMENT);
        }
    }

    private static void validateEntries(
            List<ContextSummaryDocument.Entry> entries,
            ContextSummaryGenerator.MessageCoverage expectedCoverage) {
        for (ContextSummaryDocument.Entry entry : entries) {
            for (ContextSummaryDocument.SourceRange range : entry.sourceRanges()) {
                if (range.startMessageSequence() > range.endMessageSequence()
                        || range.startMessageSequence() < expectedCoverage.startMessageSequence()
                        || range.endMessageSequence() > expectedCoverage.endMessageSequence()) {
                    throw new IllegalArgumentException(INVALID_DOCUMENT);
                }
            }
        }
    }

    private static String readSchema() {
        try (InputStream input =
                ContextSummaryDocumentValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(MISSING_SCHEMA);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(MISSING_SCHEMA, failure);
        }
    }

    /**
     * 可持久化的不可变 summary schema v1 文档。
     *
     * @param schemaVersion 固定摘要 schema 版本
     * @param coverage 文档声明的完整 turn/message 覆盖范围
     * @param confirmedFacts 已确认且仍可复用的事实
     * @param userPreferences 用户明确表达的偏好与限制
     * @param workingHypotheses 仍待验证、不能当作事实的假设
     * @param decisions 已选择的方案与有效决策
     * @param rejectedApproaches 已排除的路线及其结论
     * @param toolFindings 工具调用产生的关键发现或失败状态
     * @param openQuestions 尚未解决的问题与歧义
     * @param nextSteps 后续需要执行的具体步骤
     */
    public record ContextSummaryDocument(
            int schemaVersion,
            Coverage coverage,
            List<Entry> confirmedFacts,
            List<Entry> userPreferences,
            List<Entry> workingHypotheses,
            List<Entry> decisions,
            List<Entry> rejectedApproaches,
            List<Entry> toolFindings,
            List<Entry> openQuestions,
            List<Entry> nextSteps) {

        public ContextSummaryDocument {
            coverage = Objects.requireNonNull(coverage, "coverage");
            confirmedFacts = List.copyOf(confirmedFacts);
            userPreferences = List.copyOf(userPreferences);
            workingHypotheses = List.copyOf(workingHypotheses);
            decisions = List.copyOf(decisions);
            rejectedApproaches = List.copyOf(rejectedApproaches);
            toolFindings = List.copyOf(toolFindings);
            openQuestions = List.copyOf(openQuestions);
            nextSteps = List.copyOf(nextSteps);
        }

        /** 按 schema 固定顺序返回全部结构化条目，供统一来源范围校验。 */
        List<Entry> entries() {
            return java.util.stream.Stream.of(
                            confirmedFacts,
                            userPreferences,
                            workingHypotheses,
                            decisions,
                            rejectedApproaches,
                            toolFindings,
                            openQuestions,
                            nextSteps)
                    .flatMap(List::stream)
                    .toList();
        }

        /**
         * 摘要正文声明的完整 turn/message 闭区间。
         *
         * @param startTurnSequence 覆盖的首个完整问答序号
         * @param endTurnSequence 覆盖的最后一个完整问答序号
         * @param startMessageSequence 覆盖的首个消息序号
         * @param endMessageSequence 覆盖的最后一个消息序号
         */
        public record Coverage(
                long startTurnSequence,
                long endTurnSequence,
                long startMessageSequence,
                long endMessageSequence) {}

        /**
         * 一条可复用状态及其审计来源范围。
         *
         * @param statement 可供后续模型复用的结构化状态陈述
         * @param sourceRanges 支撑该陈述的一个或多个消息范围
         */
        public record Entry(String statement, List<SourceRange> sourceRanges) {
            public Entry {
                statement = Objects.requireNonNull(statement, "statement");
                sourceRanges = List.copyOf(sourceRanges);
            }
        }

        /**
         * 条目来源消息的闭区间。
         *
         * @param startMessageSequence 来源范围的首个消息序号
         * @param endMessageSequence 来源范围的最后一个消息序号
         */
        public record SourceRange(long startMessageSequence, long endMessageSequence) {}
    }
}
