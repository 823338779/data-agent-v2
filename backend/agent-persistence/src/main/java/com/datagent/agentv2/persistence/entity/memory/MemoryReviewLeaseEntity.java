package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 固定用户审查租约行；释放时保留单调 fencing token。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_review_lease")
public final class MemoryReviewLeaseEntity extends MemoryRow {
    private Long fencingToken;
    private String owner;
    private Instant leaseUntil;

    public static MemoryReviewLeaseEntity create() {
        var entity = new MemoryReviewLeaseEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
