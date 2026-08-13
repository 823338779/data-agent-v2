package com.datagent.agentv2.context.hierarchical.runtime;

/** 将摘要生成器的稳定失败收敛为 hierarchical runtime 边界异常。 */
public final class HierarchicalCompactionException extends RuntimeException {

    /** 保留稳定失败代码，不携带 prompt、来源或模型输出正文。 */
    public HierarchicalCompactionException(String failureCode, Throwable cause) {
        super(failureCode, cause);
    }
}
