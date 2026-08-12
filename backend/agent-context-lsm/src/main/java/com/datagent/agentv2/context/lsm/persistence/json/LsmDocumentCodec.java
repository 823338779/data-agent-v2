package com.datagent.agentv2.context.lsm.persistence.json;

import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** LSM 插件 JSON 文档的唯一编解码边界，当前用于冻结策略快照。 */
@Component
public final class LsmDocumentCodec {

    private static final String INVALID_POLICY_SNAPSHOT = "invalid LSM policy snapshot";

    private final ObjectMapper json;

    /** 使用确定性 Jackson 配置创建插件 JSON codec。 */
    public LsmDocumentCodec() {
        this(JsonMapper.builder().build());
    }

    LsmDocumentCodec(ObjectMapper json) {
        this.json = json;
    }

    /** 将策略快照写成可持久化的 JSONB 文档。 */
    public String writePolicy(ContextPolicySnapshot policy) {
        try {
            return json.writeValueAsString(policy);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_SNAPSHOT, failure);
        }
    }

    /** 从持久化 JSONB 文档读取并重新执行领域快照校验。 */
    public ContextPolicySnapshot readPolicy(String document) {
        try {
            return json.readValue(document, ContextPolicySnapshot.class);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_SNAPSHOT, failure);
        }
    }
}
