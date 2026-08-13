package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.util.List;
import java.util.Objects;

/** 按 durable planning fence 从 assembler 边界重载生成所需的短生命周期正文来源。 */
public interface HierarchicalGenerationSourceProvider {

    /** 返回严格匹配 execution 输入边界和 base checkpoint 的原始 turns/Cold 来源。 */
    GenerationSources load(GenerationSourceKey key);

    /** source lookup key 只包含 execution 与 checkpoint fencing 元数据。 */
    record GenerationSourceKey(
            ContextExecutionRef execution,
            long baseCheckpointVersion,
            long baseStrategyGeneration) {

        public GenerationSourceKey {
            execution = Objects.requireNonNull(execution, "execution");
            if (baseCheckpointVersion < 0L || baseStrategyGeneration < 0L) {
                throw new IllegalArgumentException("Invalid hierarchical generation source key");
            }
        }
    }

    /** assembler 临时返回的正文来源，不进入 planning/generation carrier 或 durable plan。 */
    record GenerationSources(List<ConversationTurn> turns, HierarchicalCheckpoint checkpoint) {

        public GenerationSources {
            turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }
}
