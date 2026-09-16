package com.datagent.agentv2.persistence.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewJobEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryReviewJobMapper extends BaseMapper<MemoryReviewJobEntity> {
    /** 使用数据库时钟校验任务与用户租约，避免 worker 主机时钟漂移绕过 fencing。 */
    @Select(
            """
            SELECT EXISTS(
              SELECT 1 FROM personal_memory_review_job j
              JOIN personal_memory_review_lease l
                ON l.tenant_id=j.tenant_id AND l.user_id=j.user_id
             WHERE j.tenant_id=#{tenantId} AND j.user_id=#{userId}
               AND j.job_id=#{jobId} AND j.conversation_id=#{conversationId}
               AND j.learning_epoch=#{epoch} AND j.fencing_token=#{jobToken}
               AND l.fencing_token=#{userToken} AND j.owner=#{owner} AND l.owner=#{owner}
               AND j.claimed_generation=#{claimedGeneration}
               AND j.reviewed_through_sequence=#{fromSequence}
               AND j.claimed_through_sequence=#{throughSequence}
               AND j.lease_until >= #{leaseUntil} AND l.lease_until >= #{leaseUntil}
               AND j.lease_until > clock_timestamp() AND l.lease_until > clock_timestamp())
            """)
    boolean validateClaim(
            String tenantId,
            String userId,
            String jobId,
            String conversationId,
            long epoch,
            long jobToken,
            long userToken,
            String owner,
            long claimedGeneration,
            long fromSequence,
            long throughSequence,
            Instant leaseUntil);
}
