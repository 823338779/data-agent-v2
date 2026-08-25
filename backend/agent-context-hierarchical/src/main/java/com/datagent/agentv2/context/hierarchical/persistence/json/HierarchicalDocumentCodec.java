package com.datagent.agentv2.context.hierarchical.persistence.json;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 冻结执行策略的严格 V2 JSON 编解码边界。 */
public final class HierarchicalDocumentCodec {
    private final ObjectMapper json = JsonMapper.builder().build();

    public String writePolicy(HierarchicalContextPolicySnapshot policy) {
        validate(policy);
        return json.writeValueAsString(policy);
    }

    public HierarchicalContextPolicySnapshot readPolicy(String document) {
        try {
            HierarchicalContextPolicySnapshot policy =
                    json.readValue(
                            Objects.requireNonNull(document, "document"),
                            HierarchicalContextPolicySnapshot.class);
            validate(policy);
            return policy;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("invalid hierarchical policy document", failure);
        }
    }

    private static void validate(HierarchicalContextPolicySnapshot policy) {
        if (policy == null || policy.policyVersion() != 2) {
            throw new IllegalArgumentException("invalid hierarchical policy document");
        }
    }
}
