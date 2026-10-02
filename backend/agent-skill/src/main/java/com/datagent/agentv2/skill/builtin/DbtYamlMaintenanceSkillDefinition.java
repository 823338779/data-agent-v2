package com.datagent.agentv2.skill.builtin;

import com.datagent.agentv2.dbt.tool.DbtValidationTool;
import com.datagent.agentv2.skill.SkillDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** YAML 维护依赖 Git 和 dbt 服务，但不拥有自动确认或发布权限。 */
@Component
@ConditionalOnExpression("${data-agent.dbt.enabled:false} and ${data-agent.git.enabled:false}")
public final class DbtYamlMaintenanceSkillDefinition implements SkillDefinition {
    @Override
    public String logicalId() {
        return "dbt-yaml-maintenance";
    }

    @Override
    public String description() {
        return "维护 dbt 指标、语义模型或 sources 的 YAML，并校验后生成 Git 确认卡片时使用。";
    }

    @Override
    public List<String> requiredTools() {
        var tools = new ArrayList<>(new GitRepositoryMaintenanceSkillDefinition().requiredTools());
        tools.addAll(DbtValidationTool.TOOL_IDS);
        return List.copyOf(tools);
    }
}
