package com.datagent.agentv2.skill;

import java.util.List;

/**
 * 由服务端代码维护的 Skill 运行时定义。
 *
 * <p>模型指令仍由 Skill 资源承载；Tool 依赖属于可执行权限契约，必须引用对应 Tool 公开的稳定标识。
 */
public interface SkillDefinition {

    /** 返回与 Skill 资源目录一致的稳定逻辑标识。 */
    String logicalId();

    /** 返回用于模型选择 Skill 的简短用途说明。 */
    String description();

    /** 返回读取主指令后需要原子激活的 Tool 标识。 */
    List<String> requiredTools();
}
