package com.datagent.agentv2.memory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 个人记忆管理界面的公开条目视图；内部指纹、提示词版本与证据正文不会穿过 HTTP 边界。 */
public record PersonalMemoryView(
        String memoryId,
        MemoryKind kind,
        String content,
        MemorySource source,
        boolean userProtected,
        long version,
        String updatedAt) {

    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    /** 从已提交领域快照提取界面所需字段，不执行查询或其他 I/O。 */
    public static PersonalMemoryView composeFromMemory(MemoryEntry memory) {
        Objects.requireNonNull(memory, "memory");
        return new PersonalMemoryView(
                memory.memoryId(),
                memory.kind(),
                memory.content(),
                memory.source(),
                memory.userProtected(),
                memory.version(),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        memory.updatedAt().atZone(BUSINESS_TIME_ZONE)));
    }
}
