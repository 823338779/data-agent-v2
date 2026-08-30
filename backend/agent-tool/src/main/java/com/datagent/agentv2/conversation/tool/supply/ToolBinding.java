package com.datagent.agentv2.conversation.tool.supply;

import com.datagent.agentv2.conversation.tool.AgentTool;
import java.util.Objects;

/**
 * 工具运行时名称与实现指纹的不可变绑定。
 *
 * <p>执行视图保存该绑定，而不是只保存名称，以便激活能力时确认启动阶段校验过的实现没有发生漂移。
 *
 * @param runtimeName 暴露给模型的稳定名称
 * @param fingerprint 描述实现身份与模型契约的 SHA-256 指纹
 * @param tool 实际执行工具
 */
public record ToolBinding(String runtimeName, String fingerprint, AgentTool tool) {

    public ToolBinding {
        runtimeName = requireText(runtimeName, "runtimeName");
        fingerprint = requireText(fingerprint, "fingerprint");
        tool = Objects.requireNonNull(tool, "tool");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
