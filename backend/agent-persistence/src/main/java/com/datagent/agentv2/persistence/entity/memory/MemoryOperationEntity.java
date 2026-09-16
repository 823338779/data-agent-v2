package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 稳定请求幂等结果；只保存摘要和结构化结果，不复制个人正文。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_operation")
public final class MemoryOperationEntity extends MemoryRow {
    /** 设置操作提交时的完整快照 JSON；普通操作为空，不包含个人正文。 */
    private String settingsResult;

    private String requestId;
    private String payloadHash;
    private String outcomeStatus;
    private String memoryId;
    private Long resultVersion;
    private Long resultRevision;
    private Boolean created;
    private Boolean targetExists;
    private Integer personProfileUsed;
    private Integer personProfileLimit;
    private Integer collaborationPreferenceUsed;
    private Integer collaborationPreferenceLimit;

    public static MemoryOperationEntity create() {
        var entity = new MemoryOperationEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
