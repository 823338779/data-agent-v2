package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class AgentToolRegistry {

    /** 注册表发现重复工具名时使用的稳定错误前缀。 */
    private static final String ERROR_DUPLICATE_TOOL = "Duplicate agent tool: ";

    /** 执行阶段请求未注册工具时使用的稳定错误前缀。 */
    private static final String ERROR_UNKNOWN_TOOL = "Unknown agent tool: ";

    /** 按 Spring 注入顺序保存工具，确保每轮模型调用收到一致的定义顺序。 */
    private final Map<String, AgentTool> toolsByName;

    /** 校验工具名唯一性并建立只读、有序索引。 */
    public AgentToolRegistry(List<AgentTool> registeredTools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();

        // 重复名称会导致模型定义与执行目标不一致，因此在应用启动阶段直接拒绝。
        for (AgentTool tool : registeredTools) {
            if (indexed.put(tool.name(), tool) != null) {
                throw new IllegalStateException(ERROR_DUPLICATE_TOOL + tool.name());
            }
        }
        this.toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    /** 按稳定名称查找工具，供需要自行处理缺失情况的调用方使用。 */
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /** 按稳定名称取得工具，缺失时抛出明确异常。 */
    public AgentTool require(String name) {
        return find(name).orElseThrow(() -> new IllegalStateException(ERROR_UNKNOWN_TOOL + name));
    }

    /** 按注册顺序构造每轮模型调用可见的工具定义。 */
    public List<ModelToolDefinition> modelDefinitions() {
        return toolsByName.values().stream()
                .map(tool -> new ModelToolDefinition(tool.name(), tool.description(), schema(tool)))
                .toList();
    }

    /** 将工具声明的 JSON Schema 转换为模型网关使用的普通映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(AgentTool tool) {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .convertValue(tool.parametersSchema(), Map.class);
    }
}
