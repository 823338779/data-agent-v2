package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 个人记忆正文行；删除必须物理删除并由数据库级联清理活动证据。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory")
public final class MemoryEntryEntity extends MemoryRow {
    private String memoryId;
    private String kind;
    private String content;
    private String normalizedHash;
    private Integer normalizationVersion;

    /** Java UTF-16 code unit 数量，不使用数据库 length 代替。 */
    private Integer charCount;

    private String source;
    private Boolean userProtected;
    private Long version;
    private LocalDateTime lastConfirmedAt;
    private String promptVersion;

    public static MemoryEntryEntity create() {
        var entity = new MemoryEntryEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
