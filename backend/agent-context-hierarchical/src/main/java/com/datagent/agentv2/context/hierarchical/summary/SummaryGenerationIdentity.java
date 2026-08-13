package com.datagent.agentv2.context.hierarchical.summary;

import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import java.util.Objects;

/** 在调用模型前即可确定的 hierarchical 摘要生成身份。 */
public record SummaryGenerationIdentity(
        /** 候选摘要所属租户，确定性 key 禁止跨租户碰撞。 */
        String tenantId,
        /** 候选摘要所属会话，禁止跨会话复用节点。 */
        String conversationSessionId,
        /** 摘要正文 JSON schema 版本。 */
        int schemaVersion,
        /** 摘要 system prompt 版本。 */
        int promptVersion,
        /** 摘要模型的稳定 provider/model 身份。 */
        ContextSummaryModelIdentity modelIdentity) {

    /** 拒绝缺少任一不可变身份维度的候选。 */
    public SummaryGenerationIdentity {
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        conversationSessionId =
                Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
        if (tenantId.isBlank()
                || conversationSessionId.isBlank()
                || schemaVersion <= 0
                || promptVersion <= 0) {
            throw new IllegalArgumentException("Invalid summary generation identity");
        }
    }
}
