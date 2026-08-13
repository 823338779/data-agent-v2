package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 校验 hierarchical 摘要的 schema、可信 coverage、字段范围和本地 token 上限。 */
public final class ContextSummaryDocumentValidator {

    /** 当前支持的摘要正文 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final String SCHEMA_RESOURCE =
            "/schema/hierarchical-context-summary-v1.schema.json";
    private static final String INVALID_DOCUMENT = "Invalid hierarchical context summary document";
    private static final String MISSING_SCHEMA = "Hierarchical context summary schema is missing";

    private final ObjectMapper json;
    private final ConversationTokenEstimator estimator;
    private final String schemaDocument;
    private final Schema schema;

    /** 使用插件本地 JSON mapper 与 token 估算器加载固定 schema。 */
    public ContextSummaryDocumentValidator() {
        this(new ConversationTokenEstimator());
    }

    /** 允许 generator 与测试显式复用同一保守 token 估算器。 */
    public ContextSummaryDocumentValidator(ConversationTokenEstimator estimator) {
        this(JsonMapper.builder().build(), estimator);
    }

    ContextSummaryDocumentValidator(ObjectMapper json, ConversationTokenEstimator estimator) {
        this.json = Objects.requireNonNull(json, "json");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.schemaDocument = readSchema();
        this.schema =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schemaDocument, InputFormat.JSON);
    }

    /** 返回原样发送给摘要 provider 的固定输出 schema。 */
    public String schemaDocument() {
        return schemaDocument;
    }

    /** 校验 schema、字段体积与本地 token 上限，不声明外部 coverage。 */
    public ValidationResult validate(String document, int maxSummaryTokens) {
        return validate(document, maxSummaryTokens, null);
    }

    /** 额外要求正文 coverage 和条目来源严格位于可信规划范围内。 */
    public ValidationResult validate(
            String document, int maxSummaryTokens, GenerationRange expectedRange) {
        try {
            if (maxSummaryTokens <= 0) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }

            // 先执行固定 schema，再转换为不可变 Java 文档，避免宽松反序列化绕过字段门禁。
            String checkedDocument = Objects.requireNonNull(document, "document");
            JsonNode node = json.readTree(checkedDocument);
            if (!schema.validate(node).isEmpty()) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }
            ContextSummaryDocument summary = json.treeToValue(node, ContextSummaryDocument.class);

            // coverage 与来源范围使用可信规划边界校验，来源正文中的冲突声明没有权限。
            validateCoverage(summary, expectedRange);
            int estimatedTokens = estimator.estimateSummary(checkedDocument);
            if (estimatedTokens > maxSummaryTokens) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }
            return new ValidationResult(summary, estimatedTokens);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID_DOCUMENT, failure);
        }
    }

    private static void validateCoverage(
            ContextSummaryDocument summary, GenerationRange expectedRange) {
        Coverage actual = summary.coverage();
        if (actual.startTurn() > actual.endTurn()
                || actual.startMessageSequence() > actual.endMessageSequence()) {
            throw new IllegalArgumentException(INVALID_DOCUMENT);
        }
        Coverage allowed = actual;
        if (expectedRange != null) {
            allowed =
                    new Coverage(
                            expectedRange.startTurn(),
                            expectedRange.endTurn(),
                            expectedRange.startMessageSequence(),
                            expectedRange.endMessageSequence());
            if (!allowed.equals(actual)) {
                throw new IllegalArgumentException(INVALID_DOCUMENT);
            }
        }

        // 即使调用者不提供外部范围，条目来源自身也必须有序且位于正文 coverage 内。
        for (Entry entry : summary.entries()) {
            for (SourceRange range : entry.sourceRanges()) {
                if (range.startMessageSequence() > range.endMessageSequence()
                        || range.startMessageSequence() < allowed.startMessageSequence()
                        || range.endMessageSequence() > allowed.endMessageSequence()) {
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

    /** 校验完成的不可变摘要正文及其本地保守 token 估算。 */
    public record ValidationResult(
            /** 已通过 schema 与 coverage 门禁的结构化正文。 */
            ContextSummaryDocument document,
            /** 使用当前 estimator 得到的非负正文 token 数。 */
            int estimatedTokenCount) {

        public ValidationResult {
            document = Objects.requireNonNull(document, "document");
            if (estimatedTokenCount < 0) {
                throw new IllegalArgumentException("Summary token estimate must not be negative");
            }
        }
    }

    /** schema v1 的七类可复用结构化状态。 */
    public record ContextSummaryDocument(
            /** 固定 schema 版本。 */
            int schemaVersion,
            /** 摘要正文覆盖的可信 turn/message 闭区间。 */
            Coverage coverage,
            /** 已确认且仍有效的事实。 */
            List<Entry> confirmedFacts,
            /** 仍待验证且不可视为事实的假设。 */
            List<Entry> workingHypotheses,
            /** 已选择的方案和仍有效的决定。 */
            List<Entry> decisions,
            /** 已排除的路线及其结论。 */
            List<Entry> rejectedApproaches,
            /** 工具结果、标识符、失败码和失败信息。 */
            List<Entry> toolFindings,
            /** 尚未完成且仍有效的执行步骤。 */
            List<Entry> nextSteps,
            /** 未解决的问题、歧义和阻塞项。 */
            List<Entry> openQuestions) {

        public ContextSummaryDocument {
            coverage = Objects.requireNonNull(coverage, "coverage");
            confirmedFacts = List.copyOf(confirmedFacts);
            workingHypotheses = List.copyOf(workingHypotheses);
            decisions = List.copyOf(decisions);
            rejectedApproaches = List.copyOf(rejectedApproaches);
            toolFindings = List.copyOf(toolFindings);
            nextSteps = List.copyOf(nextSteps);
            openQuestions = List.copyOf(openQuestions);
        }

        /** 按固定字段顺序汇总所有条目，供统一来源区间校验。 */
        List<Entry> entries() {
            return Stream.of(
                            confirmedFacts,
                            workingHypotheses,
                            decisions,
                            rejectedApproaches,
                            toolFindings,
                            nextSteps,
                            openQuestions)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    /** 摘要声明的 turn/message 闭区间。 */
    public record Coverage(
            /** 首个完整问答序号。 */
            long startTurn,
            /** 最后一个完整问答序号。 */
            long endTurn,
            /** 首条持久化消息序号。 */
            long startMessageSequence,
            /** 末条持久化消息序号。 */
            long endMessageSequence) {}

    /** 一条可复用状态及其原始消息来源范围。 */
    public record Entry(
            /** 不含逐字 reasoning 的结构化陈述。 */
            String statement,
            /** 支撑陈述的一个或多个消息闭区间。 */
            List<SourceRange> sourceRanges) {

        public Entry {
            statement = Objects.requireNonNull(statement, "statement");
            sourceRanges = List.copyOf(sourceRanges);
        }
    }

    /** 单条摘要状态的消息来源闭区间。 */
    public record SourceRange(
            /** 来源首条消息序号。 */
            long startMessageSequence,
            /** 来源末条消息序号。 */
            long endMessageSequence) {}
}
