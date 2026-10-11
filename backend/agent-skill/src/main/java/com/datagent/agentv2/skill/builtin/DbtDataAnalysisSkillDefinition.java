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
    // 摘要在 skill_read 前可见，避免“重新读取”被误当成仅复述历史结果的普通问答。
    private static final String DESCRIPTION =
            "查询已发布 dbt 指标、口径、维度、数据预览或解释分析结果时使用。"
                    + "用户要求重新查询、重新读取结果或核对结果完整性时，必须先加载本技能并实际调用工具，不能复述历史结果冒充本次执行。";

    @Override
    public String logicalId() {
        return "dbt-data-analysis";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public List<String> requiredTools() {
        return DbtAnalysisTool.TOOL_IDS;
    }
}
