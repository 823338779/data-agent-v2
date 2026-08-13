package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.conversation.context.ContextPreparationException;

/** 固定框架和不可压缩当前轮或正式发布视图无法进入物理输入上限。 */
public final class HierarchicalContextWindowExceededException extends ContextPreparationException {

    /** 宿主 execution 收敛和外部错误映射使用的稳定 failure code。 */
    public static final String FAILURE_CODE = "CONTEXT_WINDOW_EXCEEDED";

    /** 以稳定 failure code 同时作为安全异常消息，避免泄漏上下文正文。 */
    public HierarchicalContextWindowExceededException() {
        super(FAILURE_CODE, FAILURE_CODE);
    }
}
