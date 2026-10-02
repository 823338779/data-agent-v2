package com.datagent.agentv2.skill.builtin;

import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import com.datagent.agentv2.skill.SkillDefinition;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 已发布 dbt 数据分析，开启分析不要求 Git 工作区。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
public final class DbtDataAnalysisSkillDefinition implements SkillDefinition {
    @Override
    public String logicalId() {
        return "dbt-data-analysis";
    }

    @Override
    public String description() {
        return "查询已发布 dbt 指标、口径、维度、数据预览或解释分析结果时使用。";
    }

    @Override
    public List<String> requiredTools() {
        return DbtAnalysisTool.TOOL_IDS;
    }
}
