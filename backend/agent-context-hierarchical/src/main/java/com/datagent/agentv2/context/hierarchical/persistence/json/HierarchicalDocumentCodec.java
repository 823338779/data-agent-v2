package com.datagent.agentv2.context.hierarchical.persistence.json;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 插件私有 JSON 文档的版本校验和确定性编解码边界。 */
@Component
public final class HierarchicalDocumentCodec {

    private static final int SUPPORTED_POLICY_VERSION = 1;
    private static final int SUPPORTED_SUMMARY_SCHEMA_VERSION = 1;
    private static final int SUPPORTED_SUMMARY_PROMPT_VERSION = 1;
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String INVALID_POLICY_DOCUMENT = "invalid hierarchical policy document";
    private static final String INVALID_VERSIONED_DOCUMENT =
            "invalid hierarchical versioned document";

    /** 插件内所有 JSON 文档共用同一份 Jackson 配置。 */
    private final ObjectMapper json;

    public HierarchicalDocumentCodec() {
        this(JsonMapper.builder().build());
    }

    HierarchicalDocumentCodec(ObjectMapper json) {
        this.json = json;
    }

    /** 将已验证的执行策略写成 JSONB 文档。 */
    public String writePolicy(HierarchicalContextPolicySnapshot policy) {
        validatePolicy(policy);
        try {
            return json.writeValueAsString(policy);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_DOCUMENT, failure);
        }
    }

    /** 读取冻结策略并拒绝当前插件不认识的策略版本。 */
    public HierarchicalContextPolicySnapshot readPolicy(String document) {
        try {
            HierarchicalContextPolicySnapshot policy =
                    json.readValue(document, HierarchicalContextPolicySnapshot.class);
            validatePolicy(policy);
            return policy;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_DOCUMENT, failure);
        }
    }

    /** 校验通用 versioned JSON 文档并返回规范化表示。 */
    public String canonicalizeVersionedDocument(String document, int expectedSchemaVersion) {
        try {
            JsonNode root = json.readTree(document);
            if (root == null
                    || !root.isObject()
                    || root.path(SCHEMA_VERSION_FIELD).asInt(-1) != expectedSchemaVersion) {
                throw new IllegalArgumentException(INVALID_VERSIONED_DOCUMENT);
            }
            return json.writeValueAsString(root);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_VERSIONED_DOCUMENT, failure);
        }
    }

    /** 校验摘要文档及其 schema、prompt 版本均受当前插件支持。 */
    public String canonicalizeSummaryDocument(
            String document, int schemaVersion, int promptVersion) {
        if (schemaVersion != SUPPORTED_SUMMARY_SCHEMA_VERSION
                || promptVersion != SUPPORTED_SUMMARY_PROMPT_VERSION) {
            throw new IllegalStateException(INVALID_VERSIONED_DOCUMENT);
        }
        return canonicalizeVersionedDocument(document, schemaVersion);
    }

    /** 策略版本是读取持久化配置时的兼容性门禁。 */
    private static void validatePolicy(HierarchicalContextPolicySnapshot policy) {
        if (policy == null
                || policy.policyVersion() != SUPPORTED_POLICY_VERSION
                || policy.summarySchemaVersion() != SUPPORTED_SUMMARY_SCHEMA_VERSION
                || policy.summaryPromptVersion() != SUPPORTED_SUMMARY_PROMPT_VERSION) {
            throw new IllegalArgumentException(INVALID_POLICY_DOCUMENT);
        }
    }
}
