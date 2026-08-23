package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.context.common.summary.EventContextSummaryDocument;
import com.datagent.agentv2.context.common.summary.EventContextSummaryPrompt;
import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 把已发布 Cold 和零到多个原始消息批次顺序合并为一个最终 V2 Cold。 */
public final class RollingColdSummaryGenerator {
    public static final int PROMPT_VERSION = 2;
    private final ContextSummaryModelPort model;
    private final EventContextSummaryValidator validator;
    private final EventContextSummaryRenderer renderer;
    private final ConversationTokenEstimator estimator;
    private final HierarchicalColdKeyFactory keys;
    private final ObjectMapper json = JsonMapper.builder().build();

    public RollingColdSummaryGenerator(
            ContextSummaryModelPort model,
            EventContextSummaryValidator validator,
            EventContextSummaryRenderer renderer,
            ConversationTokenEstimator estimator,
            HierarchicalColdKeyFactory keys) {
        this.model = Objects.requireNonNull(model, "model");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public GeneratedCold generate(GenerationRequest request) {
        GeneratedCold current =
                request.sourceCold() == null ? null : fromPublished(request.sourceCold());
        if (request.batches().isEmpty()) {
            current = generateBatch(current, List.of(), request);
        } else {
            for (GenerationBatch batch : request.batches()) {
                current = generateBatch(current, batch.turns(), request);
            }
        }
        if (current == null || !current.coverage().equals(request.targetCoverage())) {
            throw new IllegalStateException("COMPACTION_OUTPUT_INVALID");
        }
        return current;
    }

    private GeneratedCold generateBatch(
            GeneratedCold source, List<ConversationTurn> rawTurns, GenerationRequest request) {
        EventSummaryCoverage coverage = mergedCoverage(source, rawTurns);
        String sourceDocument = sourceDocument(source, rawTurns);
        if (estimatedInputTokens(sourceDocument, coverage, request)
                <= request.summaryInputHardTokenBudget()) {
            return generateOne(source, rawTurns, request, coverage);
        }
        if (rawTurns.size() <= 1) {
            throw new IllegalStateException("COMPACTION_INPUT_OVER_BUDGET");
        }
        int middle = rawTurns.size() / 2;
        GeneratedCold first = generateBatch(source, rawTurns.subList(0, middle), request);
        return generateBatch(first, rawTurns.subList(middle, rawTurns.size()), request);
    }

    private GeneratedCold generateOne(
            GeneratedCold source,
            List<ConversationTurn> rawTurns,
            GenerationRequest request,
            EventSummaryCoverage coverage) {
        String sourceDocument = sourceDocument(source, rawTurns);
        for (int attempt = 0; attempt < 2; attempt++) {
            ContextSummaryRequest modelRequest =
                    new ContextSummaryRequest(
                            EventContextSummaryPrompt.systemPrompt(
                                    coverage,
                                    request.targetRenderedTokens(),
                                    request.maxOutputTokens(),
                                    attempt > 0),
                            sourceDocument,
                            validator.schemaDocumentFor(coverage),
                            request.targetRenderedTokens(),
                            request.maxOutputTokens(),
                            request.summaryInputHardTokenBudget());
            try {
                ContextSummaryResult result = model.summarize(modelRequest);
                requireIdentity(result, request.identity());
                EventContextSummaryDocument document =
                        validator.validate(result.content(), coverage);
                String persisted = json.writeValueAsString(document);
                String rendered = renderer.render(document);
                int persistedTokens = estimator.estimateSummary(persisted);
                int renderedTokens =
                        estimator.estimateMessages(
                                List.of(
                                        com.datagent.agentv2.conversation.context.ContextMessage
                                                .user(rendered)));
                if (result.estimatedTokenCount() > request.maxOutputTokens()
                        || renderedTokens > request.targetRenderedTokens()) {
                    throw new IllegalArgumentException("COMPACTION_OUTPUT_OVER_BUDGET");
                }
                String sourceHash = sha256(sourceDocument);
                String key =
                        keys.create(
                                request.conversationSessionId()
                                        + '\u001f'
                                        + persisted
                                        + '\u001f'
                                        + request.identity().provider()
                                        + '\u001f'
                                        + request.identity().model());
                return new GeneratedCold(
                        key,
                        persisted,
                        rendered,
                        persistedTokens,
                        renderedTokens,
                        coverage,
                        request.identity(),
                        sourceHash);
            } catch (RuntimeException invalid) {
                if (attempt == 1) {
                    throw new IllegalStateException("COMPACTION_OUTPUT_INVALID", invalid);
                }
            }
        }
        throw new IllegalStateException("COMPACTION_OUTPUT_INVALID");
    }

    private int estimatedInputTokens(
            String sourceDocument, EventSummaryCoverage coverage, GenerationRequest request) {
        String prompt =
                EventContextSummaryPrompt.systemPrompt(
                        coverage, request.targetRenderedTokens(), request.maxOutputTokens(), false);
        String schema = validator.schemaDocumentFor(coverage);
        long estimate =
                (long) estimator.estimateSummary(prompt)
                        + estimator.estimateSummary(sourceDocument)
                        + estimator.estimateSummary(schema);
        return estimate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private static EventSummaryCoverage mergedCoverage(
            GeneratedCold source, List<ConversationTurn> rawTurns) {
        if (rawTurns.isEmpty()) {
            if (source == null) {
                throw new IllegalStateException("COMPACTION_OUTPUT_INVALID");
            }
            return source.coverage();
        }
        ConversationTurn first = rawTurns.getFirst();
        ConversationTurn last = rawTurns.getLast();
        return new EventSummaryCoverage(
                source == null ? first.ordinal() : source.coverage().startTurnSequence(),
                last.ordinal(),
                source == null
                        ? first.startMessageSequence()
                        : source.coverage().startMessageSequence(),
                last.endMessageSequence());
    }

    private String sourceDocument(GeneratedCold source, List<ConversationTurn> rawTurns) {
        List<String> sections = new ArrayList<>();
        if (source != null) {
            sections.add("[现有事件摘要]\n" + source.document());
        }
        if (!rawTurns.isEmpty()) {
            sections.add("[新增原始对话]\n" + json.writeValueAsString(rawTurns));
        }
        return String.join("\n\n", sections);
    }

    private static GeneratedCold fromPublished(HierarchicalCold cold) {
        return new GeneratedCold(
                cold.coldId(),
                cold.summaryDocument(),
                "",
                cold.persistedTokenCount(),
                cold.renderedTokenCount(),
                cold.coverage(),
                new ContextSummaryModelIdentity(cold.modelProvider(), cold.modelName()),
                cold.sourceContentSha256());
    }

    private static void requireIdentity(
            ContextSummaryResult result, ContextSummaryModelIdentity expected) {
        if (!expected.provider().equals(result.modelProvider())
                || !expected.model().equals(result.modelName())) {
            throw new IllegalArgumentException("Summary model identity changed");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public record GenerationRequest(
            String tenantId,
            String conversationSessionId,
            HierarchicalCold sourceCold,
            List<GenerationBatch> batches,
            EventSummaryCoverage targetCoverage,
            int targetRenderedTokens,
            int maxOutputTokens,
            int summaryInputHardTokenBudget,
            ContextSummaryModelIdentity identity) {
        public GenerationRequest {
            batches = List.copyOf(batches);
            if (targetRenderedTokens <= 0
                    || maxOutputTokens <= 0
                    || summaryInputHardTokenBudget <= 0) {
                throw new IllegalArgumentException("Summary budgets must be positive");
            }
        }
    }

    public record GenerationBatch(EventSummaryCoverage coverage, List<ConversationTurn> turns) {
        public GenerationBatch {
            turns = List.copyOf(turns);
        }
    }

    public record GeneratedCold(
            String coldKey,
            String document,
            String renderedText,
            int persistedTokenCount,
            int renderedTokenCount,
            EventSummaryCoverage coverage,
            ContextSummaryModelIdentity identity,
            String sourceContentSha256) {}
}
