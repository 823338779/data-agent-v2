package com.datagent.agentv2.context.hierarchical.runtime;

/** 暴露已持久化压缩边界的默认空观察点，供崩溃恢复验收精确停止进程。 */
@FunctionalInterface
public interface HierarchicalCompactionBoundaryHook {

    /** 生产默认实现不产生副作用。 */
    HierarchicalCompactionBoundaryHook NO_OP = (boundary, durableCandidateCount) -> {};

    /** durable 状态已提交或候选已写入后通知观察者。 */
    void reached(Boundary boundary, int durableCandidateCount);

    /** 可恢复的三个持久化生命周期边界。 */
    enum Boundary {
        PLANNED,
        GENERATING,
        READY_TO_PUBLISH
    }
}
