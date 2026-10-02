package com.datagent.agentv2.conversation.tool.supply;

import com.datagent.agentv2.conversation.tool.AgentTool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.stereotype.Service;

/**
 * 汇总进程内全部 Tool 供给并生成稳定绑定。
 *
 * <p>该注册表只表达“系统装载了什么”，不表达某次执行可以看到什么；执行级权限由 {@code ExecutionToolView} 单独维护。
 */
@Service
public final class ToolSupplyRegistry {

    private static final String ERROR_DUPLICATE_TOOL = "Duplicate agent tool: ";
    private static final String ERROR_UNKNOWN_TOOL = "Unknown agent tool: ";

    private final Map<String, ToolBinding> bindingsByName;

    /** Spring 注册顺序的不可变绑定列表，供执行视图保持模型 Tool 顺序稳定。 */
    @Getter
    @Accessors(fluent = true)
    private final List<ToolBinding> bindingsInRegistrationOrder;

    /** 按 Spring 注入顺序建立不可变索引，重复名称会在启动阶段直接失败。 */
    public ToolSupplyRegistry(List<AgentTool> registeredTools) {
        Map<String, ToolBinding> indexed = new LinkedHashMap<>();
        for (AgentTool tool : List.copyOf(registeredTools)) {
            ToolBinding binding = bind(tool);
            if (indexed.put(binding.runtimeName(), binding) != null) {
                throw new IllegalStateException(ERROR_DUPLICATE_TOOL + binding.runtimeName());
            }
        }
        this.bindingsByName = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
        this.bindingsInRegistrationOrder = List.copyOf(indexed.values());
    }

    /** 返回已供给工具名称的稳定有序集合。 */
    public Set<String> names() {
        return bindingsByName.keySet();
    }

    /** 按运行时名称取得绑定；配置引用不存在的 Tool 时以启动错误处理。 */
    public ToolBinding require(String runtimeName) {
        ToolBinding binding = bindingsByName.get(runtimeName);
        if (binding == null) {
            throw new IllegalStateException(ERROR_UNKNOWN_TOOL + runtimeName);
        }
        return binding;
    }

    private static ToolBinding bind(AgentTool tool) {
        String runtimeName = tool.name();
        if (runtimeName == null || runtimeName.isBlank()) {
            throw new IllegalArgumentException("runtimeName must not be blank");
        }
        String source =
                runtimeName
                        + "\n"
                        + tool.getClass().getName()
                        + "\n"
                        + tool.description()
                        + "\n"
                        + tool.parametersSchema();
        return new ToolBinding(runtimeName, sha256(source), tool);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }
}
