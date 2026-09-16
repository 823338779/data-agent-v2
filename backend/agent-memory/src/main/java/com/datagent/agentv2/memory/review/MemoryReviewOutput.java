package com.datagent.agentv2.memory.review;

import java.util.List;
import java.util.Map;

/** 正常结束与维护请求明确分离；模型异常必须传播，不能伪装成无新事实。 */
public sealed interface MemoryReviewOutput {
    record Calls(List<Call> calls) implements MemoryReviewOutput {
        public Calls {
            calls = List.copyOf(calls);
            if (calls.isEmpty()) throw new IllegalArgumentException("空工具调用");
        }
    }

    record Done() implements MemoryReviewOutput {}

    record Call(String providerCallId, Map<String, Object> arguments) {
        public Call {
            arguments = Map.copyOf(arguments);
        }
    }
}
