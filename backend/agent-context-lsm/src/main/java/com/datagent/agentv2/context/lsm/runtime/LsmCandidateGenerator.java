package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.mapping.LsmPersistenceStructMapper;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/** 逐项生成并幂等保存当前压缩调用需要的不可变摘要段。 */
@RequiredArgsConstructor
public final class LsmCandidateGenerator {

    private static final String SEGMENT_ID_PREFIX = "context_summary_segment";
    private static final String SYSTEM_OPERATOR = "lsm-context";
    private static final String PLANNED_ID_PREFIX = "planned-";

    private final ContextSummaryGenerator generator;
    private final LsmSummarySegmentRepository segments;
    private final LsmDocumentCodec documents;
    private final IdGenerator ids;

    public GeneratedCompaction generate(CompactionRequest request, LsmCompactionPlan plan) {
        Map<String, CandidateInput> inputs = new LinkedHashMap<>();
        request.candidateInputs().forEach(input -> inputs.put(input.targetId(), input));
        Map<String, String> resolvedIds = new HashMap<>();
        Map<String, String> generatedDocuments = new HashMap<>();
        List<LsmSummarySegment> candidates = new ArrayList<>();
        for (LsmCompactionPlan.PlannedOperation operation : plan.operations()) {
            if (operation.targetId() == null) {
                continue;
            }
            CandidateInput input =
                    Objects.requireNonNull(inputs.get(operation.targetId()), "candidate input");
            ContextSummaryGenerator.GenerationRequest generation =
                    resolvedGenerationRequest(input, generatedDocuments);
            String segmentKey = generator.segmentKey(generation, request.policySnapshot());
            LsmSummarySegmentEntity persisted =
                    segments.findBySegmentKey(
                                    request.execution().conversationSessionId(), segmentKey)
                            .orElse(null);
            if (persisted == null) {
                GeneratedSummary summary = generator.generate(generation, request.policySnapshot());
                persisted =
                        persistCandidate(
                                request,
                                operation,
                                input,
                                generation,
                                resolveSourceIds(
                                        input.generationRequest().sourceIds(), resolvedIds),
                                summary);
            }
            resolvedIds.put(operation.targetId(), persisted.getSummarySegmentId());
            generatedDocuments.put(operation.targetId(), persisted.getSummaryDocument());
            candidates.add(LsmPersistenceStructMapper.INSTANCE.toDomain(persisted, documents));
        }
        return new GeneratedCompaction(resolvePlan(plan, resolvedIds), candidates);
    }

    private LsmSummarySegmentEntity persistCandidate(
            CompactionRequest request,
            LsmCompactionPlan.PlannedOperation operation,
            CandidateInput input,
            ContextSummaryGenerator.GenerationRequest generation,
            List<String> sourceIds,
            GeneratedSummary summary) {
        LocalDateTime now = LocalDateTime.now();
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId(ids.next(SEGMENT_ID_PREFIX));
        entity.setConversationSessionId(request.execution().conversationSessionId());
        entity.setSegmentKey(summary.segmentKey());
        entity.setKind(operation.targetKind());
        entity.setLevel(operation.targetLevel());
        entity.setStartTurnSequence(operation.coverage().startTurnSequence());
        entity.setEndTurnSequence(operation.coverage().endTurnSequence());
        entity.setStartMessageSequence(generation.messageCoverage().startMessageSequence());
        entity.setEndMessageSequence(generation.messageCoverage().endMessageSequence());
        entity.setPartialSegment(operation.partialSegment());
        entity.setSummaryDocument(summary.serializedJson());
        entity.setEstimatedTokenCount(summary.estimatedTokenCount());
        entity.setSourceSegmentIds(documents.writeStringList(sourceIds));
        entity.setSourceContentSha256(sha256(generation.sourceDocument()));
        entity.setSummarySchemaVersion(EventContextSummaryValidator.SCHEMA_VERSION);
        entity.setPromptVersion(ContextSummaryGenerator.PROMPT_VERSION);
        entity.setModelProvider(summary.provider());
        entity.setModelName(summary.model());
        entity.setCreationPolicy(documents.writePolicy(request.policySnapshot()));
        entity.setSourceSegmentCount(sourceIds.size());
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(0L);
        return segments.findOrCreate(entity);
    }

    private ContextSummaryGenerator.GenerationRequest resolvedGenerationRequest(
            CandidateInput input, Map<String, String> generatedDocuments) {
        var original = input.generationRequest();
        if (input.summarySources().isEmpty()) {
            return original;
        }
        List<String> sourceDocuments =
                input.summarySources().stream()
                        .map(
                                source ->
                                        source.planned()
                                                ? Objects.requireNonNull(
                                                        generatedDocuments.get(source.sourceId()),
                                                        "planned summary source")
                                                : source.summaryDocument())
                        .toList();
        return new ContextSummaryGenerator.GenerationRequest(
                original.kind(),
                original.level(),
                original.turnCoverage(),
                original.messageCoverage(),
                original.sourceIds(),
                documents.writeSummaryDocuments(sourceDocuments),
                original.targetRenderedTokens());
    }

    private static List<String> resolveSourceIds(
            List<String> sourceIds, Map<String, String> resolvedIds) {
        return sourceIds.stream()
                .map(
                        id -> {
                            String resolved = resolvedIds.get(id);
                            if (resolved == null && id.startsWith(PLANNED_ID_PREFIX)) {
                                throw new IllegalStateException(
                                        "planned summary source is unresolved");
                            }
                            return resolved == null ? id : resolved;
                        })
                .toList();
    }

    private static LsmCompactionPlan resolvePlan(
            LsmCompactionPlan plan, Map<String, String> resolvedIds) {
        List<LsmCompactionPlan.PlannedOperation> operations =
                plan.operations().stream()
                        .map(
                                operation ->
                                        new LsmCompactionPlan.PlannedOperation(
                                                operation.type(),
                                                resolve(operation.targetId(), resolvedIds),
                                                operation.sourceIds().stream()
                                                        .map(id -> resolve(id, resolvedIds))
                                                        .toList(),
                                                operation.targetKind(),
                                                operation.targetLevel(),
                                                operation.coverage(),
                                                operation.partialSegment(),
                                                operation.estimatedTokenCount(),
                                                operation.targetRenderedTokens()))
                        .toList();
        List<LsmSummarySegment> projected =
                plan.projectedSegments().stream()
                        .map(
                                segment ->
                                        new LsmSummarySegment(
                                                resolve(segment.segmentId(), resolvedIds),
                                                segment.kind(),
                                                segment.level(),
                                                segment.coverage(),
                                                segment.estimatedTokenCount(),
                                                segment.sourceSegmentIds().stream()
                                                        .map(id -> resolve(id, resolvedIds))
                                                        .toList(),
                                                segment.partialSegment()))
                        .toList();
        LsmContextManifest manifest = plan.proposedManifest();
        LsmContextManifest resolvedManifest =
                new LsmContextManifest(
                        resolve(manifest.activeRootSegmentId(), resolvedIds),
                        manifest.activeSegmentIds().stream()
                                .map(id -> resolve(id, resolvedIds))
                                .toList(),
                        manifest.pendingRawStartTurnSequence(),
                        manifest.pendingRawEndTurnSequence(),
                        manifest.generation(),
                        manifest.effectiveFanout(),
                        manifest.effectiveMaxLevel());
        return new LsmCompactionPlan(plan.hotBoundary(), operations, resolvedManifest, projected);
    }

    private static String resolve(String id, Map<String, String> resolvedIds) {
        return id == null ? null : resolvedIds.getOrDefault(id, id);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record GeneratedCompaction(LsmCompactionPlan plan, List<LsmSummarySegment> candidates) {
        public GeneratedCompaction {
            candidates = List.copyOf(candidates);
        }
    }
}
