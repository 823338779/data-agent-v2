package com.datagent.agentv2.context.lsm.summary;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import java.util.Objects;

/** 决定摘要候选语义的完整稳定生成身份。 */
public record SummaryGenerationIdentity(
        /** 摘要正文 JSON schema 版本。 */
        int schemaVersion,
        /** 摘要系统提示词版本。 */
        int promptVersion,
        /** execution 冻结的完整压缩策略快照。 */
        LsmContextPolicySnapshot policySnapshot,
        /** 调用前可确定的摘要模型供应商与名称。 */
        ContextSummaryModelIdentity modelIdentity) {

    public SummaryGenerationIdentity {
        policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
        if (schemaVersion <= 0 || promptVersion <= 0) {
            throw new IllegalArgumentException("summary generation versions must be positive");
        }
    }
}
