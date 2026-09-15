package com.datagent.agentv2.skill.builtin;

import com.datagent.agentv2.git.tool.*;
import com.datagent.agentv2.skill.SkillDefinition;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仓库维护指令仅在 Git 能力启用时供给；用户确认始终保留在 HTTP 卡片按钮。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public final class GitRepositoryMaintenanceSkillDefinition implements SkillDefinition {
    @Override
    public String logicalId() {
        return "git-repository-maintenance";
    }

    @Override
    public String description() {
        return "维护指标 Git 仓库、修改文件并生成待用户确认的提交卡片时使用。";
    }

    @Override
    public List<String> requiredTools() {
        return List.of(
                GitWorkspacePrepareTool.TOOL_ID,
                GitFilesListTool.TOOL_ID,
                GitFilesSearchTool.TOOL_ID,
                GitFileReadTool.TOOL_ID,
                GitFilesApplyTool.TOOL_ID,
                GitDiffTool.TOOL_ID,
                GitChangePrepareTool.TOOL_ID);
    }
}
