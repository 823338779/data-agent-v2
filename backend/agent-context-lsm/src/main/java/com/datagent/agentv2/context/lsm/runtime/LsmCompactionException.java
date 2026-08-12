package com.datagent.agentv2.context.lsm.runtime;

/** 对外只暴露稳定代码和无正文消息的压缩失败。 */
public final class LsmCompactionException extends RuntimeException {

    public static final String FAILURE_CODE = "CONTEXT_COMPACTION_FAILED";
    private static final String SAFE_MESSAGE = "Context compaction failed";

    private final String failureCode;

    private LsmCompactionException(String failureCode, String safeMessage) {
        super(safeMessage);
        this.failureCode = failureCode;
    }

    /** 创建摘要尝试耗尽后的稳定异常，不保留可能携带正文的 cause。 */
    public static LsmCompactionException summaryFailed() {
        return new LsmCompactionException(FAILURE_CODE, SAFE_MESSAGE);
    }

    public String failureCode() {
        return failureCode;
    }
}
