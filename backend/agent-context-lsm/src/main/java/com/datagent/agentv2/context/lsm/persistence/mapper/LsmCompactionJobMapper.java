package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 插件私有的可恢复压缩作业 SQL 映射。 */
@Mapper
public interface LsmCompactionJobMapper extends BaseMapper<LsmCompactionJobEntity> {

    String TENANT_LINE_IGNORED = "true";
    String STATUS_PLANNED = "PLANNED";
    String STATUS_GENERATING = "GENERATING";
    String STATUS_READY = "READY";
    String SELECT_RECOVERABLE_JOB =
            "select * from conversation_context_lsm_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and status in ('"
                    + STATUS_PLANNED
                    + "', '"
                    + STATUS_GENERATING
                    + "', '"
                    + STATUS_READY
                    + "') and deleted = false";

    /** 读取给定 execution 边界上仍可继续执行的作业。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Select(SELECT_RECOVERABLE_JOB)
    LsmCompactionJobEntity selectRecoverableJob(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary);
}
