package com.datagent.agentv2.context.hierarchical.runtime;

/** 将摘要生成器的稳定失败收敛为 hierarchical runtime 边界异常。 */
public final class HierarchicalCompactionException extends RuntimeException {

    /** execution、job 和外部错误映射共同使用的稳定失败码。 */
    public static final String FAILURE_CODE = "CONTEXT_COMPACTION_FAILED";

    /** 不泄漏摘要正文、prompt 或来源数据的安全错误消息。 */
    public static final String SAFE_MESSAGE = "Hierarchical context compaction failed";

    /** 保留稳定失败代码，不携带 prompt、来源或模型输出正文。 */
    public HierarchicalCompactionException(String failureCode, Throwable cause) {
        super(failureCode, cause);
    }
}
