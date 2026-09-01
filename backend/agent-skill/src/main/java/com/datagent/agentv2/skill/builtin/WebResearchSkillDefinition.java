package com.datagent.agentv2.skill.builtin;

import com.datagent.agentv2.conversation.tool.builtin.WebFetchTool;
import com.datagent.agentv2.conversation.tool.builtin.WebSearchTool;
import com.datagent.agentv2.skill.SkillDefinition;
import java.util.List;
import org.springframework.stereotype.Component;

/** 为联网研究 Skill 绑定服务端允许激活的搜索与网页读取 Tool。 */
@Component
public final class WebResearchSkillDefinition implements SkillDefinition {

    private static final String LOGICAL_ID = "web-research";
    private static final String DESCRIPTION = "当问题需要最新信息或外部公开资料时使用。";
    private static final List<String> REQUIRED_TOOLS =
            List.of(WebSearchTool.TOOL_ID, WebFetchTool.TOOL_ID);

    @Override
    public String logicalId() {
        return LOGICAL_ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public List<String> requiredTools() {
        return REQUIRED_TOOLS;
    }
}
