package com.datagent.agentv2.skill;

import java.util.List;

/**
 * @param logicalId Skill 稳定逻辑标识 @param description 仅向模型公开的摘要 @param requiredTools 读取主指令后原子激活的 Tool
 */
public record SkillDescriptor(String logicalId, String description, List<String> requiredTools) {
    public SkillDescriptor {
        requiredTools = List.copyOf(requiredTools);
    }
}
