package com.datagent.agentv2.conversation.stream;

/** 唯一定位一次执行尝试中某次模型调用的本地流累加器。 */
record ExecutionStreamKey(
        /** Agent 执行业务标识。 */
        String executionId,
        /** 执行所有权尝试序号。 */
        long executionAttemptNumber,
        /** 当前尝试内的模型调用序号。 */
        long modelInvocationSequence) {}
