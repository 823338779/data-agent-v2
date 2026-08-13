package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCheckpointPublicationTransaction.PublishResult;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.CompactionRequest;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService.RecoveryAction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService.RecoveryResult;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.Objects;

/** 协调 durable 恢复、事务外生成、原子发布以及发布后的预算复验。 */
public final class HierarchicalCompactionCoordinator {

    private static final int MAX_PUBLICATION_ATTEMPTS = 3;
    private static final String RETRY_EXHAUSTED =
            "Hierarchical checkpoint publication retries were exhausted";

    private final HierarchicalCompactionPlanningTransaction planning;
    private final HierarchicalCandidateGenerationService generation;
    private final HierarchicalCompactionRecoveryService recovery;
    private final HierarchicalCheckpointPublicationTransaction publication;
    private final HierarchicalConversationContextAssembler assembler;
    private final HierarchicalCheckpointRepository checkpoints;
    private final ContextExecutionControlPort executions;
    private final int maxSummaryTokens;

    /** 固定恢复、生成、发布、重建和 execution 门禁依赖。 */
    public HierarchicalCompactionCoordinator(
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            HierarchicalCompactionRecoveryService recovery,
            HierarchicalCheckpointPublicationTransaction publication,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCheckpointRepository checkpoints,
            ContextExecutionControlPort executions,
            int maxSummaryTokens) {
        this.planning = Objects.requireNonNull(planning, "planning");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.publication = Objects.requireNonNull(publication, "publication");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.executions = Objects.requireNonNull(executions, "executions");
        if (maxSummaryTokens <= 0) {
            throw new IllegalArgumentException("maxSummaryTokens must be positive");
        }
        this.maxSummaryTokens = maxSummaryTokens;
    }

    /** stale CAS 后只从最新 checkpoint 重建状态，并以有界循环重新进入恢复/规划。 */
    public CompactionOutcome compactIfRequired(HierarchicalContextState initialState) {
        HierarchicalContextState current = Objects.requireNonNull(initialState, "initialState");
        for (int attempt = 0; attempt < MAX_PUBLICATION_ATTEMPTS; attempt++) {
            RecoveryResult recovered =
                    recovery.recover(current.request().execution(), current.checkpoint());
            if (recovered.action() == RecoveryAction.COMPLETED) {
                return verifyPublished(
                        recovered.publishedCheckpoint(), current.request(), current.policy());
            }
            if (recovered.action() == RecoveryAction.CANCELLED) {
                return currentOutcome(current);
            }
            if (recovered.action() == RecoveryAction.FAILED) {
                if (recovered.reportFailure()) {
                    executions.fail(
                            current.request().execution(),
                            HierarchicalCompactionException.FAILURE_CODE,
                            HierarchicalCompactionException.SAFE_MESSAGE);
                }
                throw new HierarchicalCompactionException(
                        HierarchicalCompactionException.FAILURE_CODE, null);
            }

            ReadyCompaction ready;
            if (recovered.action() == RecoveryAction.PUBLISH) {
                ready = recovered.ready();
            } else {
                HierarchicalConversationContextAssembler.CompactionInput input;
                try {
                    input = assembler.toCompactionInput(current);
                } catch (HierarchicalContextWindowExceededException hardFailure) {
                    executions.fail(
                            current.request().execution(),
                            HierarchicalContextWindowExceededException.FAILURE_CODE,
                            HierarchicalContextWindowExceededException.FAILURE_CODE);
                    throw hardFailure;
                }
                PlannedCompaction planned =
                        planning.plan(
                                new CompactionRequest(
                                        current.request().execution(),
                                        current.policy(),
                                        maxSummaryTokens),
                                input.planningInput());
                ready = generation.generate(planned);
                if (ready.cancelled()) {
                    return currentOutcome(current);
                }
            }

            if (publication.publish(ready) == PublishResult.PUBLISHED) {
                HierarchicalCheckpoint published =
                        checkpoints.findCheckpoint(
                                current.request().execution().conversationSessionId());
                if (published == null) {
                    throw new IllegalStateException(RETRY_EXHAUSTED);
                }
                return verifyPublished(published, current.request(), current.policy());
            }
            current = assembler.loadAndEstimate(current.request(), current.policy());
        }
        throw new IllegalStateException(RETRY_EXHAUSTED);
    }

    private CompactionOutcome verifyPublished(
            HierarchicalCheckpoint checkpoint,
            ConversationContextRequest request,
            HierarchicalContextPolicySnapshot policy) {
        PreparedConversationContext prepared;
        try {
            prepared = assembler.preparePublishedView(checkpoint, request, policy);
        } catch (HierarchicalContextWindowExceededException hardFailure) {
            // publication 已原子提交时，重建视图的 hard-limit 失败仍须同步到 execution 状态。
            executions.fail(
                    request.execution(),
                    HierarchicalContextWindowExceededException.FAILURE_CODE,
                    HierarchicalContextWindowExceededException.FAILURE_CODE);
            throw hardFailure;
        }
        boolean targetReached =
                prepared.estimatedInputTokens() <= policy.targetInputTokens(request.budget());
        boolean withinHardLimit =
                prepared.estimatedInputTokens() <= request.budget().hardInputLimitTokens();
        if (!withinHardLimit) {
            executions.fail(
                    request.execution(),
                    HierarchicalContextWindowExceededException.FAILURE_CODE,
                    HierarchicalContextWindowExceededException.FAILURE_CODE);
            throw new HierarchicalContextWindowExceededException();
        }
        return new CompactionOutcome(checkpoint, targetReached, true);
    }

    private static CompactionOutcome currentOutcome(HierarchicalContextState state) {
        boolean targetReached =
                state.estimatedTokens()
                        <= state.policy().targetInputTokens(state.request().budget());
        boolean withinHardLimit =
                state.estimatedTokens() <= state.request().budget().hardInputLimitTokens();
        return new CompactionOutcome(state.checkpoint(), targetReached, withinHardLimit);
    }

    /** 原子发布后的 checkpoint 及目标线、物理 hard limit 结果。 */
    public record CompactionOutcome(
            HierarchicalCheckpoint checkpoint, boolean targetReached, boolean withinHardLimit) {

        public CompactionOutcome {
            Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }
}
