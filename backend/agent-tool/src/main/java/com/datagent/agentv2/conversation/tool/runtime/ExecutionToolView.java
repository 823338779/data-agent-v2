package com.datagent.agentv2.conversation.tool.runtime;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.supply.ToolBinding;
import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单次 Agent 执行的 Tool 可见性快照。
 *
 * <p>所有读取和激活操作都在同一对象锁上完成。激活先完整校验名称、最大权限和绑定指纹，再一次性修改可见集合，避免部分激活使执行状态不可审计。
 */
public final class ExecutionToolView {

    private final ToolSupplyRegistry supply;
    private final List<ToolBinding> maximumBindings;
    private final Map<String, ToolBinding> maximumByName;
    private final Set<String> visibleNames;

    ExecutionToolView(
            ToolSupplyRegistry supply,
            List<ToolBinding> maximumBindings,
            Collection<String> initialNames) {
        this.supply = supply;
        this.maximumBindings = List.copyOf(maximumBindings);
        this.maximumByName =
                maximumBindings.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ToolBinding::runtimeName,
                                        Function.identity(),
                                        (left, right) -> left));
        this.visibleNames = new LinkedHashSet<>(initialNames);
    }

    /** 返回按系统注册顺序排列的当前可见 Tool 绑定。 */
    public synchronized List<ToolBinding> visibleTools() {
        return maximumBindings.stream()
                .filter(binding -> visibleNames.contains(binding.runtimeName()))
                .toList();
    }

    /** 只解析当前执行可见的 Tool，拒绝时不在安全消息中泄露隐藏名称。 */
    public synchronized ToolBinding requireVisible(String runtimeName)
            throws ToolBusinessException {
        if (!visibleNames.contains(runtimeName)) {
            throw new ToolBusinessException(
                    "tool_not_visible", "Tool is not visible for this execution");
        }
        return maximumByName.get(runtimeName);
    }

    /**
     * 原子激活一组 Tool。
     *
     * <p>若任一名称越过 maximum 或绑定指纹已变化，整组请求都会失败且不会改变可见集合。
     */
    public synchronized ToolActivationResult activateAll(Collection<String> runtimeNames)
            throws ToolBusinessException {
        LinkedHashSet<String> requested = new LinkedHashSet<>(runtimeNames);
        for (String runtimeName : requested) {
            ToolBinding allowed = maximumByName.get(runtimeName);
            if (allowed == null
                    || !allowed.fingerprint().equals(supply.require(runtimeName).fingerprint())) {
                throw new ToolBusinessException(
                        "skill_activation_denied",
                        "Skill requested tools outside execution policy");
            }
        }

        boolean alreadyActivated = visibleNames.containsAll(requested);
        visibleNames.addAll(requested);
        return new ToolActivationResult(List.copyOf(requested), alreadyActivated);
    }

    /** 返回最大允许集合，顺序与系统 Tool 注册顺序一致。 */
    public List<String> maximumNames() {
        return maximumBindings.stream().map(ToolBinding::runtimeName).toList();
    }

    /** 返回当前可见集合，顺序与系统 Tool 注册顺序一致。 */
    public synchronized List<String> visibleNames() {
        return visibleTools().stream().map(ToolBinding::runtimeName).toList();
    }
}
