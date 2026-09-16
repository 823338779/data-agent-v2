package com.datagent.agentv2.memory.tool;

/** 记忆工具提示词及其稳定版本；版本随影响模型写入判断的文案同步推进。 */
public final class MemoryToolPrompts {

    public static final String MAIN_VERSION = "memory-tool-main-v1";

    public static final String MAIN =
            """
            Manage durable personal memory for future conversations with the current user.
            Save one independently maintainable fact or preference per entry. Preserve all scope and conditions.
            Use person_profile for stable facts about the person and collaboration_preference for enduring ways of working.
            Compare with existing entries before adding. Reuse equivalent entries and replace only a stable ID with its expected version.
            Current user instructions and authoritative information take priority over stored memory.
            Ask for clarification when the target of replace or remove is ambiguous.
            Only a successful tool result means the memory operation succeeded; report every failure honestly.
            """;

    private MemoryToolPrompts() {}
}
