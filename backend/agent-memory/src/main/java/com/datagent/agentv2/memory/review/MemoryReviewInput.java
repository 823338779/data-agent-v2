package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemorySnapshot;
import java.util.List;
import java.util.Map;

/** 单次后台调用冻结的全量小库、完整证据及私有工具协议历史；上限是完整输入的 token 硬限。 */
public record MemoryReviewInput(
        MemorySnapshot snapshot,
        List<MemoryEvidence> evidence,
        List<Map<String, Object>> history,
        int maxInputTokens) {
    public MemoryReviewInput {
        evidence = List.copyOf(evidence);
        history = history.stream().map(Map::copyOf).toList();
    }
}
