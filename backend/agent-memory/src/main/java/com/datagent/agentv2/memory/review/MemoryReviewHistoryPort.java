package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.MemoryReviewClaim;
import java.util.List;

/** 按持久化序号分页读取完整正文，不读取工具过程、摘要或运行时注入内容。 */
public interface MemoryReviewHistoryPort {
    List<MemoryReviewMessage> read(MemoryReviewClaim claim, long afterSequence, int limit);
}
