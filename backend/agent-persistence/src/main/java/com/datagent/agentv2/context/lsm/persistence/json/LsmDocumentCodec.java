package com.datagent.agentv2.context.lsm.persistence.json;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** LSM 插件 JSON 文档的唯一编解码边界，当前用于冻结策略快照。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "lsm")
@lombok.RequiredArgsConstructor(access = lombok.AccessLevel.PACKAGE)
public final class LsmDocumentCodec {

    private static final String INVALID_POLICY_SNAPSHOT = "invalid LSM policy snapshot";
    private static final String INVALID_STRING_LIST = "invalid LSM string list";
    private static final String INVALID_SUMMARY_SOURCES = "invalid LSM summary sources";

    private final ObjectMapper json;

    /** 使用确定性 Jackson 配置创建插件 JSON codec。 */
    public LsmDocumentCodec() {
        this(JsonMapper.builder().build());
    }

    /** 将策略快照写成可持久化的 JSONB 文档。 */
    public String writePolicy(LsmContextPolicySnapshot policy) {
        try {
            return json.writeValueAsString(policy);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_SNAPSHOT, failure);
        }
    }

    /** 从持久化 JSONB 文档读取并重新执行领域快照校验。 */
    public LsmContextPolicySnapshot readPolicy(String document) {
        try {
            return json.readValue(document, LsmContextPolicySnapshot.class);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_POLICY_SNAPSHOT, failure);
        }
    }

    /** 将有序来源标识编码为 JSONB 数组。 */
    public String writeStringList(List<String> values) {
        try {
            return json.writeValueAsString(List.copyOf(values));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_STRING_LIST, failure);
        }
    }

    /** 从 JSONB 数组恢复稳定有序的字符串列表。 */
    public List<String> readStringList(String document) {
        try {
            return List.copyOf(
                    json.readValue(
                            document,
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_STRING_LIST, failure);
        }
    }

    /** 将有序直接子摘要解析为结构化 JSON 数组，避免把 JSON 正文二次转义为普通字符串。 */
    public String writeSummaryDocuments(List<String> summaryDocuments) {
        try {
            var result = json.createArrayNode();
            for (String document : summaryDocuments) {
                result.add(json.readTree(document));
            }
            return json.writeValueAsString(result);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_SUMMARY_SOURCES, failure);
        }
    }
}
