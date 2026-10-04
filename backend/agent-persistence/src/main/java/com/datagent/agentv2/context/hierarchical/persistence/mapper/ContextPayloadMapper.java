package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.ContextPayloadEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 幂等归档使用 PostgreSQL ON CONFLICT，避免唯一键竞争使外层事务失效。 */
@Mapper
public interface ContextPayloadMapper extends BaseMapper<ContextPayloadEntity> {
    String INSERT_IGNORE =
            """
            insert into conversation_context_payload
            (reference, tenant_id, conversation_session_id, execution_id, process_item_id, direction, sha256, total_bytes, content)
            values (#{reference}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{processItemId}, #{direction}, #{sha256}, #{totalBytes}, #{content})
            on conflict (reference) do nothing
            """;

    @Insert(INSERT_IGNORE)
    int insertIgnore(ContextPayloadEntity payload);
}
