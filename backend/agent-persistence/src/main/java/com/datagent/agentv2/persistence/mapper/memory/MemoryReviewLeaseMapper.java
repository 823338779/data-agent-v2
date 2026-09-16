package com.datagent.agentv2.persistence.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewLeaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryReviewLeaseMapper extends BaseMapper<MemoryReviewLeaseEntity> {
    /** 数据库时钟是租约有效性的唯一可信时间源。 */
    @Select(
            """
            SELECT EXISTS(
              SELECT 1 FROM personal_memory_review_lease
               WHERE tenant_id=#{tenantId} AND user_id=#{userId}
                 AND fencing_token=#{userToken} AND owner=#{owner}
                 AND lease_until > clock_timestamp())
            """)
    boolean validateLease(String tenantId, String userId, long userToken, String owner);
}
