package com.datagent.agentv2.context.lsm.summary;

import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.SegmentKind;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 根据所有影响摘要候选语义的稳定输入生成确定性 segment key。 */
public final class LsmSegmentKeyFactory {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String ITEM_SEPARATOR = "\u001e";
    private static final String HASH_UNAVAILABLE = "SHA-256 is unavailable";

    /** 将完整稳定生成身份与候选来源一起散列，防止跨 schema、策略或模型复用。 */
    public String create(GenerationRequest request, SummaryGenerationIdentity identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        var policy = identity.policySnapshot();
        var model = identity.modelIdentity();
        return create(
                request.kind(),
                request.level(),
                request.turnCoverage().startTurnSequence(),
                request.turnCoverage().endTurnSequence(),
                request.messageCoverage().startMessageSequence(),
                request.messageCoverage().endMessageSequence(),
                request.sourceIds(),
                request.sourceDocument(),
                identity.schemaVersion(),
                identity.promptVersion(),
                policy.policyVersion(),
                policy.fanout(),
                policy.maxLevel(),
                policy.baseSegmentTurns(),
                policy.compressionThresholdPercent(),
                policy.targetWatermarkPercent(),
                policy.warmTargetTurns(),
                policy.warmMaxTurns(),
                policy.protectedHotTurns(),
                model.provider(),
                model.model());
    }

    /** 相同来源、覆盖、层级和协议版本总是返回同一个 key。 */
    public String create(
            SegmentKind kind,
            int level,
            long startTurnSequence,
            long endTurnSequence,
            long startMessageSequence,
            long endMessageSequence,
            List<String> sourceIds,
            String sourceDocument,
            int schemaVersion,
            int promptVersion,
            int policyVersion,
            int fanout,
            int maxLevel,
            int baseSegmentTurns,
            int compressionThresholdPercent,
            int targetWatermarkPercent,
            int warmTargetTurns,
            int warmMaxTurns,
            int protectedHotTurns,
            String modelProvider,
            String modelName) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceIds, "sourceIds");
        Objects.requireNonNull(sourceDocument, "sourceDocument");
        String material =
                String.join(
                        FIELD_SEPARATOR,
                        Integer.toString(schemaVersion),
                        Integer.toString(promptVersion),
                        Integer.toString(policyVersion),
                        Integer.toString(fanout),
                        Integer.toString(maxLevel),
                        Integer.toString(baseSegmentTurns),
                        Integer.toString(compressionThresholdPercent),
                        Integer.toString(targetWatermarkPercent),
                        Integer.toString(warmTargetTurns),
                        Integer.toString(warmMaxTurns),
                        Integer.toString(protectedHotTurns),
                        modelProvider,
                        modelName,
                        kind.name(),
                        Integer.toString(level),
                        Long.toString(startTurnSequence),
                        Long.toString(endTurnSequence),
                        Long.toString(startMessageSequence),
                        Long.toString(endMessageSequence),
                        String.join(ITEM_SEPARATOR, sourceIds),
                        sourceDocument);
        try {
            byte[] digest =
                    MessageDigest.getInstance(HASH_ALGORITHM)
                            .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(HASH_UNAVAILABLE, failure);
        }
    }
}
