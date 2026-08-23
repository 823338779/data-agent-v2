package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.conversation.context.ContextPreparationException;

/** 最近十个完整轮次或当前进行中轮次自身已经超过上下文硬边界。 */
public final class LsmProtectedContextOverflowException extends ContextPreparationException {

    public static final String FAILURE_CODE = "PROTECTED_CONTEXT_OVERFLOW";
    public static final String SAFE_MESSAGE =
            "Protected conversation context exceeds the configured threshold";

    public LsmProtectedContextOverflowException() {
        super(FAILURE_CODE, SAFE_MESSAGE);
    }
}
