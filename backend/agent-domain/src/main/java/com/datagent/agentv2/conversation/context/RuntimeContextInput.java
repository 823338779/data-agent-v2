package com.datagent.agentv2.conversation.context;

import java.util.List;

/**
 * 单次模型调用附带的运行时输入；必要元数据始终保留，可选贡献再接受各自与完整输入预算筛选。
 *
 * @param metadata 不参与可选贡献预算淘汰的必要消息
 * @param contributions 可按完整块逐条省略的运行时贡献
 */
public record RuntimeContextInput(
        List<ContextMessage> metadata, List<RuntimeContextContribution> contributions) {

    public RuntimeContextInput {
        metadata = List.copyOf(metadata);
        contributions = List.copyOf(contributions);
    }

    public static RuntimeContextInput empty() {
        return new RuntimeContextInput(List.of(), List.of());
    }
}
