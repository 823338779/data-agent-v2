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
        /** 人工维护的保护标志；后台学习不得覆盖或删除受保护条目。 */
        boolean userProtected,
        /** 条目 CAS 版本，从 1 开始；编辑和删除必须提交最新值。 */
        long version,
        /** 已提交更新时刻，采用 Asia/Shanghai 的 ISO_OFFSET_DATE_TIME 格式。 */
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
