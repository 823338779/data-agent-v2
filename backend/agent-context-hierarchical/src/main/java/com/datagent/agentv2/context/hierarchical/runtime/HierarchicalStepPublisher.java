package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalStepSummaryGenerator;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** 生成结束后在短事务内持有 execution 锁、核验来源并发布，模型调用不进入事务。 */
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class HierarchicalStepPublisher {
    private static final String LOG_CAS =
            "Step summary publish casWon={} coveredMessage={} coveredInvocation={} generation={}";
    private final HierarchicalConversationHistoryLoader history;
    private final HierarchicalColdRepository colds;
    private final HierarchicalCheckpointRepository checkpoints;
    private final ContextExecutionControlPort control;

    @Transactional
    public HierarchicalCheckpoint publish(
            ConversationContextRequest request,
            HierarchicalCheckpoint expected,
            HierarchicalCold candidate,
            ContextStepCursor evaluatedThrough) {
        // adapter 的行锁加入本事务，取消和摘要发布按同一 execution 串行化。
        control.assertRunningAndWithinDeadline(request.execution());
        var sources = history.loadSteps(request.execution().conversationSessionId());
        var through = candidate.stepCoverage().throughInclusive();
        if (sources.stream()
                        .noneMatch(
                                group ->
                                        group.complete()
                                                && group.throughInclusive().equals(through))
                || !HierarchicalStepSummaryGenerator.digestPrefix(sources, through)
                        .equals(candidate.stepCoverage().sourceDigest())) {
            throw new IllegalStateException("Frozen summary source changed before publication");
        }
        // 每一组都必须构成从起点开始的完整连续前缀，不能只校验最后一个终点。
        var previous = candidate.stepCoverage().fromExclusive();
        for (var group : sources) {
            if (group.throughInclusive().compareTo(through) > 0) {
                break;
            }
            if (!group.complete() || !group.fromExclusive().equals(previous)) {
                throw new IllegalStateException("Frozen summary prefix is incomplete");
            }
            previous = group.throughInclusive();
        }
        var stored = colds.insertOrSelect(candidate);
        var replacement =
                new HierarchicalCheckpoint(
                        expected.checkpointId(),
                        expected.conversationSessionId(),
                        stored.coldId(),
                        Math.max(
                                expected.evaluatedUpToConversationMessageSequence(),
                                evaluatedThrough.messageSequence()),
                        expected.strategyGeneration() + 1,
                        expected.version(),
                        through,
                        evaluatedThrough,
                        3,
                        3);
        // CAS 失败只重读胜者。没有被指针引用的候选不会进入后续模型上下文。
        boolean won = checkpoints.compareAndSetCheckpoint(expected, replacement);
        log.debug(
                LOG_CAS,
                won,
                through.messageSequence(),
                through.modelInvocationSequence(),
                replacement.strategyGeneration());
        return checkpoints.findCheckpoint(expected.conversationSessionId());
    }
}
