package com.datagent.agentv2.conversation.tool.runtime;

import com.datagent.agentv2.conversation.tool.supply.ToolBinding;
import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 进程内 executionId 到 Tool 可见性快照的生命周期容器。
 *
 * <p>执行入口负责 open/close；重复打开同一执行标识会被拒绝，防止并发执行意外共享能力状态。
 */
@Service
public final class ExecutionToolViews {

    private final ToolSupplyRegistry supply;
    private final List<ToolBinding> maximumBindings;
    private final List<String> initialNames;
    private final ConcurrentMap<String, ExecutionToolView> views = new ConcurrentHashMap<>();

    public ExecutionToolViews(ToolSupplyRegistry supply, ToolPolicyProperties policy) {
        this.supply = supply;
        validateUnique("maximum", policy.maximum());
        validateUnique("initial", policy.initial());

        Set<String> maximumNames = Set.copyOf(policy.maximum());
        if (!maximumNames.containsAll(policy.initial())) {
            throw new IllegalStateException("Initial tools must be a subset of maximum tools");
        }
        for (String configuredName : maximumNames) {
            supply.require(configuredName);
        }

        this.maximumBindings =
                supply.bindingsInRegistrationOrder().stream()
                        .filter(binding -> maximumNames.contains(binding.runtimeName()))
                        .toList();
        Set<String> initialSet = Set.copyOf(policy.initial());
        this.initialNames =
                maximumBindings.stream()
                        .map(ToolBinding::runtimeName)
                        .filter(initialSet::contains)
                        .toList();
    }

    /** 为一次执行创建唯一 Tool 快照。 */
    public ExecutionToolView open(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        ExecutionToolView created = new ExecutionToolView(supply, maximumBindings, initialNames);
        ExecutionToolView previous = views.putIfAbsent(executionId, created);
        if (previous != null) {
            throw new IllegalStateException("Execution tool view already exists: " + executionId);
        }
        return created;
    }

    /** 取得已打开的执行快照。 */
    public ExecutionToolView require(String executionId) {
        ExecutionToolView view = views.get(executionId);
        if (view == null) {
            throw new IllegalStateException("Execution tool view does not exist: " + executionId);
        }
        return view;
    }

    /** 清理执行快照；重复清理保持幂等，便于 finally 收敛异常路径。 */
    public void close(String executionId) {
        views.remove(executionId);
    }

    /** 判断执行快照是否仍存在，主要用于生命周期验证和诊断。 */
    public boolean contains(String executionId) {
        return views.containsKey(executionId);
    }

    private static void validateUnique(String field, List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "Configured " + field + " tool name must not be blank");
            }
            if (!seen.add(name)) {
                throw new IllegalStateException("Duplicate " + field + " tool: " + name);
            }
        }
    }
}
