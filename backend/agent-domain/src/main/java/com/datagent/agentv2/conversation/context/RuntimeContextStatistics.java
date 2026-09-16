package com.datagent.agentv2.conversation.context;

/** 最终模型输入的运行时贡献统计；候选数为 injected 与 omitted 之和，不包含必要证据元数据。 */
public record RuntimeContextStatistics(
        /** 实际保留的完整候选块数。 */
        int injected,
        /** 预筛及预算筛选省略的完整候选块数。 */
        int omitted,
        /** 最终贡献消息含包裹与协议开销的策略估算 token 数。 */
        int contributionTokens) {
    public static final RuntimeContextStatistics ZERO = new RuntimeContextStatistics(0, 0, 0);

    public RuntimeContextStatistics {
        if (injected < 0 || omitted < 0 || contributionTokens < 0) {
            throw new IllegalArgumentException("运行时贡献统计不得为负数");
        }
    }
}
