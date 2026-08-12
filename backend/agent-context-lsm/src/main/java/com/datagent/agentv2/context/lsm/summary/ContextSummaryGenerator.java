package com.datagent.agentv2.context.lsm.summary;

import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.SegmentKind;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator.ContextSummaryDocument;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.List;
import java.util.Objects;

/** 生成、限额并校验不可变 LSM 摘要候选，失败时只允许一次 provider 重试。 */
public final class ContextSummaryGenerator {

    private static final int MAX_ATTEMPTS = 2;
    private static final int ROOT_OUTPUT_TOKENS = 4_096;
    private static final int STANDARD_BASE_OUTPUT_TOKENS = 1_536;
    private static final int STANDARD_LEVEL_INCREMENT_TOKENS = 512;
    private static final int STANDARD_MAX_OUTPUT_TOKENS = 3_584;
    private static final String NO_VALID_DOCUMENT = "Summary model returned no valid document";

    private final ContextSummaryModelPort model;
    private final ContextSummaryDocumentValidator validator;
    private final ConversationTokenEstimator estimator;
    private final LsmSegmentKeyFactory keyFactory;

    public ContextSummaryGenerator(
            ContextSummaryModelPort model,
            ContextSummaryDocumentValidator validator,
            ConversationTokenEstimator estimator,
            LsmSegmentKeyFactory keyFactory) {
        this.model = Objects.requireNonNull(model, "model");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
    }

    /** 调用中立摘要端口，最多两次，并且绝不截断无效或超限 JSON。 */
    public GeneratedSummary generate(GenerationRequest generationRequest) {
        GenerationRequest request = Objects.requireNonNull(generationRequest, "generationRequest");
        int maxTokens = maxSummaryTokens(request.kind(), request.level());
        ContextSummaryRequest modelRequest =
                new ContextSummaryRequest(
                        ContextSummaryPrompt.systemPrompt(
                                request.turnCoverage().startTurnSequence(),
                                request.turnCoverage().endTurnSequence(),
                                request.messageCoverage().startMessageSequence(),
                                request.messageCoverage().endMessageSequence()),
                        request.sourceDocument(),
                        validator.schemaDocument(),
                        maxTokens);
        String segmentKey = segmentKey(request);

        // provider 输出无效时复用完全相同的协议、来源和 key，只允许一次重试。
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ContextSummaryResult result;
            try {
                result = model.summarize(modelRequest);
            } catch (RuntimeException providerFailure) {
                continue;
            }
            try {
                ContextSummaryDocument document =
                        validator.validate(
                                result.content(),
                                request.turnCoverage(),
                                request.messageCoverage());
                int localEstimate = estimator.estimateSummaryDocument(result.content());
                if (result.estimatedTokenCount() > maxTokens || localEstimate > maxTokens) {
                    continue;
                }
                int persistedEstimate = Math.max(result.estimatedTokenCount(), localEstimate);
                return new GeneratedSummary(
                        document,
                        result.content(),
                        persistedEstimate,
                        result.modelProvider(),
                        result.modelName(),
                        segmentKey);
            } catch (IllegalArgumentException invalidSummary) {
                // 正文及 schema 错误不得进入日志或异常消息，下一轮只复用安全元数据。
            }
        }
        throw new IllegalStateException(NO_VALID_DOCUMENT);
    }

    /** 返回 kind/level 对应的固定严格输出上限。 */
    static int maxSummaryTokens(SegmentKind kind, int level) {
        if (kind == SegmentKind.ROOT) {
            return ROOT_OUTPUT_TOKENS;
        }
        long levelCap =
                (long) STANDARD_BASE_OUTPUT_TOKENS + (long) STANDARD_LEVEL_INCREMENT_TOKENS * level;
        return (int) Math.min(levelCap, STANDARD_MAX_OUTPUT_TOKENS);
    }

    /** 在调用模型前计算候选的确定性 key，供 job 持久化和恢复查重。 */
    public String segmentKey(GenerationRequest request) {
        return keyFactory.create(
                request.kind(),
                request.level(),
                request.turnCoverage().startTurnSequence(),
                request.turnCoverage().endTurnSequence(),
                request.messageCoverage().startMessageSequence(),
                request.messageCoverage().endMessageSequence(),
                request.sourceIds(),
                request.sourceDocument());
    }

    /**
     * 一次候选生成的全部业务来源，不接受 fixed system/tool 内容参数。
     *
     * @param kind 候选摘要段种类
     * @param level 候选摘要段逻辑层级
     * @param turnCoverage 可信规划器给出的精确问答覆盖范围
     * @param messageCoverage 可信规划器给出的精确消息覆盖范围
     * @param sourceIds 按时间顺序排列的直接来源段标识
     * @param sourceDocument 作为不可信数据发送给摘要模型的来源正文
     */
    public record GenerationRequest(
            SegmentKind kind,
            int level,
            LsmSummarySegment.Coverage turnCoverage,
            MessageCoverage messageCoverage,
            List<String> sourceIds,
            String sourceDocument) {

        public GenerationRequest {
            kind = Objects.requireNonNull(kind, "kind");
            turnCoverage = Objects.requireNonNull(turnCoverage, "turnCoverage");
            messageCoverage = Objects.requireNonNull(messageCoverage, "messageCoverage");
            sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
            sourceDocument = Objects.requireNonNull(sourceDocument, "sourceDocument");
            if (level < 0) {
                throw new IllegalArgumentException("level must not be negative");
            }
            if (sourceIds.isEmpty() || sourceDocument.isBlank()) {
                throw new IllegalArgumentException("summary sources must not be empty");
            }
        }
    }

    /**
     * 摘要候选必须精确覆盖的消息闭区间。
     *
     * @param startMessageSequence 覆盖的首个消息序号
     * @param endMessageSequence 覆盖的最后一个消息序号
     */
    public record MessageCoverage(long startMessageSequence, long endMessageSequence) {
        public MessageCoverage {
            if (startMessageSequence <= 0L || endMessageSequence < startMessageSequence) {
                throw new IllegalArgumentException("invalid message coverage");
            }
        }
    }

    /**
     * 校验完成且可安全持久化的不可变摘要候选。
     *
     * @param document 已通过 schema 和覆盖范围校验的结构化文档
     * @param serializedJson 可持久化的原始 JSON 文本
     * @param estimatedTokenCount provider 与本地估算中的较大值
     * @param provider 生成摘要的模型供应商
     * @param model 生成摘要的模型名称
     * @param segmentKey 可跨重试复用的确定性 segment key
     */
    public record GeneratedSummary(
            ContextSummaryDocument document,
            String serializedJson,
            int estimatedTokenCount,
            String provider,
            String model,
            String segmentKey) {

        public GeneratedSummary {
            document = Objects.requireNonNull(document, "document");
            serializedJson = Objects.requireNonNull(serializedJson, "serializedJson");
            provider = Objects.requireNonNull(provider, "provider");
            model = Objects.requireNonNull(model, "model");
            segmentKey = Objects.requireNonNull(segmentKey, "segmentKey");
        }
    }
}
