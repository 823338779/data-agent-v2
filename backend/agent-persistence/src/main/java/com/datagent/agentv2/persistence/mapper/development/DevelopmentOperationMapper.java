package com.datagent.agentv2.persistence.mapper.development;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.development.DevelopmentOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 复用 MyBatis-Plus 单表持久能力。 */
@Mapper
public interface DevelopmentOperationMapper extends BaseMapper<DevelopmentOperationEntity> {
    // 首次受理尚无业务行，使用 PostgreSQL 事务锁保护缺席键；普通 CRUD 仍由框架完成。
    @Select("select pg_advisory_xact_lock(hashtextextended(#{scope},0))::text")
    String lockScope(@Param("scope") String scope);
}
