package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalExecutionPolicyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 插件私有的 execution 策略快照 SQL 映射。 */
@Mapper
public interface HierarchicalExecutionPolicyMapper
        extends BaseMapper<HierarchicalExecutionPolicyEntity> {

    String SELECT_POLICY =
            "select * from conversation_context_hierarchical_execution_policy where tenant_id = #{tenantId} and execution_id = #{executionId} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_execution_policy (hierarchical_execution_policy_id, tenant_id, conversation_session_id, execution_id, policy_document, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalExecutionPolicyId}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{policyDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, execution_id) do nothing";

    /** 按当前租户和 execution 读取首次冻结的策略。 */
    @Select(SELECT_POLICY)
    HierarchicalExecutionPolicyEntity selectPolicy(
            @Param("tenantId") String tenantId, @Param("executionId") String executionId);

    /** 唯一键冲突时保留首次成功写入的策略快照。 */
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalExecutionPolicyEntity policy);
}
