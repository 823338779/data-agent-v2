package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.RollingColdCompactionPlanner;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator.GeneratedCold;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator.GenerationBatch;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import java.util.List;
import java.util.Objects;

/**
 * 在当前 execution 内同步生成滚动 Cold，并通过 checkpoint CAS 发布。
 *
 * <p>Cold 是覆盖连续历史轮次的不可变摘要；checkpoint 是会话当前可见 Cold 的唯一指针。摘要生成前后都检查执行状态，发布时使用 CAS，防止并发请求覆盖更新后的
 * checkpoint。
 */
public final class HierarchicalCompactionCoordinator {

    private final RollingColdCompactionPlanner planner;
    private final RollingColdSummaryGenerator generator;
    private final HierarchicalColdRepository colds;
    private final HierarchicalCheckpointRepository checkpoints;
    private final HierarchicalConversationContextAssembler assembler;
    private final ContextExecutionControlPort executionControl;
    private final int maxOutputTokens;
    private final int summaryInputHardTokenBudget;

    public HierarchicalCompactionCoordinator(
            RollingColdCompactionPlanner planner,
            RollingColdSummaryGenerator generator,
            HierarchicalColdRepository colds,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalConversationContextAssembler assembler,
            ContextExecutionControlPort executionControl,
            int maxOutputTokens,
            int summaryInputHardTokenBudget) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.colds = Objects.requireNonNull(colds, "colds");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl");
        this.maxOutputTokens = maxOutputTokens;
        this.summaryInputHardTokenBudget = summaryInputHardTokenBudget;
    }

    public CompactionOutcome compactIfRequired(
            HierarchicalContextState state, ContextSummaryModelIdentity identity) {
        var execution = state.request().execution();
        executionControl.assertRunningAndWithinDeadline(execution);
        var input = assembler.compactionInput(state, summaryInputHardTokenBudget);
        RollingColdCompactionPlan plan =
                planner.plan(
                        state.loadedThroughMessageSequence(),
                        input.sourceCold(),
                        state.turns(),
                        state.policy().recentRawTurns(),
                        input.historyMemoryBudget(),
                        input.summaryInputHardTokenBudget());
        if (plan.targetCoverage().isEmpty()) {
            return new CompactionOutcome(state.checkpoint(), false);
        }
        GeneratedCold generated =
                generator.generate(generationRequest(state, plan, input.sourceCold(), identity));
        // 模型调用期间执行可能被取消或超时，持久化候选摘要前必须再次建立有效性。
        executionControl.assertRunningAndWithinDeadline(execution);
        HierarchicalCold candidate = colds.insertOrSelect(toCold(state, plan, generated));
        HierarchicalCheckpoint replacement =
                new HierarchicalCheckpoint(
                        state.checkpoint().checkpointId(),
                        state.checkpoint().conversationSessionId(),
                        candidate.coldId(),
                        state.loadedThroughMessageSequence(),
                        state.checkpoint().strategyGeneration() + 1L,
                        state.checkpoint().version());
        if (!checkpoints.compareAndSetCheckpoint(state.checkpoint(), replacement)) {
            // 失败表示其他写入者已推进 checkpoint；静默覆盖会造成摘要覆盖范围倒退。
            throw new IllegalStateException("Hierarchical checkpoint publication lost its CAS");
        }
        return new CompactionOutcome(
                checkpoints.findCheckpoint(execution.conversationSessionId()), true);
    }

    private GenerationRequest generationRequest(
            HierarchicalContextState state,
            RollingColdCompactionPlan plan,
            HierarchicalCold source,
            ContextSummaryModelIdentity identity) {
        List<GenerationBatch> batches =
                plan.batches().stream()
                        .map(
                                batch ->
                                        new GenerationBatch(
                                                new EventSummaryCoverage(
                                                        batch.startTurn(),
                                                        batch.endTurn(),
                                                        batch.startMessageSequence(),
                                                        batch.endMessageSequence()),
                                                state.turns().stream()
                                                        .filter(
                                                                turn ->
                                                                        turn.ordinal()
                                                                                        >= batch
                                                                                                .startTurn()
                                                                                && turn.ordinal()
                                                                                        <= batch
                                                                                                .endTurn())
                                                        .toList()))
                        .toList();
        ConversationTurn first =
                state.turns().get(Math.toIntExact(plan.targetCoverage().startTurn() - 1));
        ConversationTurn last =
                state.turns().get(Math.toIntExact(plan.targetCoverage().endTurn() - 1));
        return new GenerationRequest(
                state.request().execution().tenantId(),
                state.request().execution().conversationSessionId(),
                source,
                batches,
                new EventSummaryCoverage(
                        first.ordinal(),
                        last.ordinal(),
                        first.startMessageSequence(),
                        last.endMessageSequence()),
                plan.historyMemoryBudget(),
                maxOutputTokens,
                summaryInputHardTokenBudget,
                identity);
    }

    private static HierarchicalCold toCold(
            HierarchicalContextState state,
            RollingColdCompactionPlan plan,
            GeneratedCold generated) {
        return new HierarchicalCold(
                generated.coldKey(),
                state.request().execution().conversationSessionId(),
                generated.coverage(),
                generated.document(),
                generated.persistedTokenCount(),
                generated.renderedTokenCount(),
                plan.historyMemoryBudget(),
                generated.identity().provider(),
                generated.identity().model(),
                generated.sourceContentSha256());
    }

    /**
     * @param checkpoint 压缩后应作为后续上下文装配起点的最新 checkpoint
     * @param compacted 本次调用是否生成并成功发布了新的 Cold
     */
    public record CompactionOutcome(HierarchicalCheckpoint checkpoint, boolean compacted) {}
}
