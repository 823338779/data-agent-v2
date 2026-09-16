package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话审查任务行，持久化连续游标、冻结范围、代次和任务级 fencing。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_review_job")
public final class MemoryReviewJobEntity extends MemoryRow {
    private String jobId;
    private String conversationId;
    private Long learningEpoch;
    private Long reviewedThroughSequence;
    private Long pendingThroughSequence;
    private Long claimedThroughSequence;
    private Long dirtyGeneration;
    private Long claimedGeneration;
    private String status;
    private LocalDateTime availableAt;
    private Integer failureCount;
    private String failureReason;
    private String owner;
    private Long fencingToken;
    private Instant leaseUntil;

    public static MemoryReviewJobEntity create() {
        var entity = new MemoryReviewJobEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
