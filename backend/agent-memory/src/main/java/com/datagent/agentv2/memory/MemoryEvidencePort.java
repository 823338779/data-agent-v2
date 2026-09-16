package com.datagent.agentv2.memory;

import java.util.List;

/** 对话证据查询边界；实现须参与调用方事务，返回持久化消息，不调用模型或开启独立事务。 */
public interface MemoryEvidencePort {
    List<MemoryEvidence> resolve(MemoryWriteContext context, List<String> evidenceIds);
}
