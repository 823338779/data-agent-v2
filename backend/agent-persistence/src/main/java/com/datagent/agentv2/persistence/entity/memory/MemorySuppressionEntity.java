package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 已删除正文的最小抑制指纹；跨分类生效且不保留正文。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_suppression")
public final class MemorySuppressionEntity extends MemoryRow {
    private Integer normalizationVersion;
    private String normalizedHash;
    private Long deletedRevision;
    private LocalDateTime deletedAt;
    private String deleteOperationId;

    public static MemorySuppressionEntity create() {
        var entity = new MemorySuppressionEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
