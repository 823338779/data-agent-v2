package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Map;

/** 服务端直接读取的当前执行事实；模型摘要不能覆盖或生成这些授权记录。 */
public record ContextExecutionFacts(
        /** 标量身份、修订与确认状态；空集合表示没有绑定 Git 开发目标。 */
        Map<String, String> values) {
    public ContextExecutionFacts {
        values = Map.copyOf(values);
    }

    public static ContextExecutionFacts empty() {
        return new ContextExecutionFacts(Map.of());
    }
}
