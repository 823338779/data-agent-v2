package com.datagent.agentv2.skill.runtime;

import com.datagent.agentv2.skill.BundledSkillCatalog;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理 executionId 到 Skill 快照的进程内生命周期。 */
@Service
@RequiredArgsConstructor
public final class ExecutionSkillViews {
    private final BundledSkillCatalog catalog;
    private final ConcurrentMap<String, ExecutionSkillView> views = new ConcurrentHashMap<>();

    public ExecutionSkillView open(String executionId) {
        ExecutionSkillView created = new ExecutionSkillView(catalog);
        if (views.putIfAbsent(executionId, created) != null)
            throw new IllegalStateException("Execution skill view already exists: " + executionId);
        return created;
    }

    public ExecutionSkillView require(String executionId) {
        ExecutionSkillView view = views.get(executionId);
        if (view == null)
            throw new IllegalStateException("Execution skill view does not exist: " + executionId);
        return view;
    }

    public void close(String executionId) {
        views.remove(executionId);
    }

    public boolean contains(String executionId) {
        return views.containsKey(executionId);
    }
}
