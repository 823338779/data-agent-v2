package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/**
 * 一类可选运行时上下文的独立预算贡献。
 *
 * @param key 贡献来源的稳定低基数标识
 * @param wrapper 作为 USER 消息首部发送的消费约束和来源说明
 * @param blocks 已完成预筛的完整候选块；组合器只能整块保留或省略
 * @param maxTokens 该贡献包含消息协议开销后的独立 token 上限
 * @param candidateCount 预筛前候选总数，用于把条数预筛与预算省略统一计数
 */
public record RuntimeContextContribution(
        String key, String wrapper, List<String> blocks, int maxTokens, int candidateCount) {

    public RuntimeContextContribution {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(wrapper, "wrapper");
        blocks = List.copyOf(blocks);
        if (key.isBlank() || wrapper.isBlank()) {
            throw new IllegalArgumentException("运行时贡献标识和包裹不能为空");
        }
        if (maxTokens <= 0 || candidateCount < blocks.size()) {
            throw new IllegalArgumentException("运行时贡献预算或候选数量无效");
        }
        blocks.forEach(block -> Objects.requireNonNull(block, "block"));
    }

    /** 简易调用方没有预筛时，候选总数等于传入完整块数量。 */
    public RuntimeContextContribution(
            String key, String wrapper, List<String> blocks, int maxTokens) {
        this(key, wrapper, blocks, maxTokens, Objects.requireNonNull(blocks, "blocks").size());
    }
}
