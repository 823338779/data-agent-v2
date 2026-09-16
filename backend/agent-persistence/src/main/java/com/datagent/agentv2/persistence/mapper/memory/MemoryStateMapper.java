package com.datagent.agentv2.persistence.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.memory.MemoryStateEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryStateMapper extends BaseMapper<MemoryStateEntity> {
    /** 使用真实数据库时刻和显式业务时区，不受事务起点及数据库会话时区影响。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")
    LocalDateTime currentBusinessTime();

    /** PostgreSQL 冲突忽略保证并发首次访问不会让事务进入 aborted 状态。 */
    @Insert(
            """
            INSERT INTO personal_memory_state
                (tenant_id,user_id,revision,settings_version,read_enabled,explicit_write_enabled,
                 auto_learning_enabled,learning_epoch,learning_enabled_at,created_at,updated_at)
            VALUES
                (#{tenantId},#{userId},0,0,true,true,true,1,
                 clock_timestamp() AT TIME ZONE 'Asia/Shanghai',
                 clock_timestamp() AT TIME ZONE 'Asia/Shanghai',
                 clock_timestamp() AT TIME ZONE 'Asia/Shanghai')
            ON CONFLICT (tenant_id,user_id) DO NOTHING
            """)
    int initialize(MemoryStateEntity entity);
}
