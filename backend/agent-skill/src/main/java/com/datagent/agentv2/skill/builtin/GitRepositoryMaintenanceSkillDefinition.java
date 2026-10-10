package com.datagent.agentv2.skill.builtin;

import com.datagent.agentv2.development.tool.*;
import com.datagent.agentv2.skill.SkillDefinition;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仓库维护指令仅在 Git 能力启用时供给；授权完整动作后由持久开发用例执行。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public final class GitRepositoryMaintenanceSkillDefinition implements SkillDefinition {
    @Override
    public String logicalId() {
        return "git-repository-maintenance";
    }

    @Override
    public String description() {
        return "维护受控Git工程、修改文件并保存到会话固定分支时使用。";
    }

    @Override
    public List<String> requiredTools() {
        return List.of(
                DevelopmentFilesListTool.TOOL_ID,
                DevelopmentFilesSearchTool.TOOL_ID,
                DevelopmentFileReadTool.TOOL_ID,
                DevelopmentFilesApplyTool.TOOL_ID,
                DevelopmentDiffTool.TOOL_ID,
                DevelopmentSaveTool.TOOL_ID);
    }
}
