package com.datagent.agentv2.conversation.tool.runtime;

import java.util.List;

/**
 * 一次受限 Tool 激活结果。
 *
 * @param activatedToolNames 本次请求覆盖的 Tool 名称，保持请求顺序
 * @param alreadyActivated 请求中的全部 Tool 是否在调用前已经可见
 */
public record ToolActivationResult(List<String> activatedToolNames, boolean alreadyActivated) {

    public ToolActivationResult {
        activatedToolNames = List.copyOf(activatedToolNames);
    }
}
