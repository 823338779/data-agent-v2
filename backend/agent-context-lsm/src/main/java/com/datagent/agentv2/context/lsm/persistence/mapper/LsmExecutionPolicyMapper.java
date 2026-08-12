package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmExecutionPolicyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 插件私有的 execution 策略快照 SQL 映射。 */
@Mapper
public interface LsmExecutionPolicyMapper extends BaseMapper<LsmExecutionPolicyEntity> {

    /** 读取 execution 第一次使用 LSM 时已冻结的策略。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select(
            "select * from conversation_context_lsm_execution_policy where tenant_id = #{tenantId} and execution_id = #{executionId} and deleted = false")
    LsmExecutionPolicyEntity selectPolicy(
            @Param("tenantId") String tenantId, @Param("executionId") String executionId);

    /** 唯一键冲突时不覆盖既有策略，确保执行期间策略不可变。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            "insert into conversation_context_lsm_execution_policy (execution_policy_id, tenant_id, conversation_session_id, execution_id, policy_snapshot, created_at, created_by, updated_at, updated_by, deleted, version) values (#{executionPolicyId}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{policySnapshot, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, execution_id) do nothing")
    int insertIgnore(LsmExecutionPolicyEntity policy);
}
