package com.datagent.agentv2.skill;

import java.util.Map;

/** Skill 打包资源来源。 */
public interface SkillResourceSource {
    Map<String, byte[]> load();
}
