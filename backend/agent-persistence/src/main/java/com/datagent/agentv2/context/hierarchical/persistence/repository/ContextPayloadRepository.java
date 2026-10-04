package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.context.hierarchical.persistence.entity.ContextPayloadEntity;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.ContextPayloadMapper;
import com.datagent.agentv2.platform.context.TenantContext;

/** 只通过租户和会话限定查询安全正文，不提供路径/SQL/URL 读取能力。 */
public class ContextPayloadRepository
        extends ServiceImpl<ContextPayloadMapper, ContextPayloadEntity> {
    public ContextPayloadEntity insertOrSelect(ContextPayloadEntity candidate) {
        baseMapper.insertIgnore(candidate);
        var stored = findVisible(candidate.getConversationSessionId(), candidate.getReference());
        if (stored == null
                || !stored.getSha256().equals(candidate.getSha256())
                || !stored.getExecutionId().equals(candidate.getExecutionId())
                || !stored.getProcessItemId().equals(candidate.getProcessItemId())
                || !stored.getDirection().equals(candidate.getDirection())) {
            throw new IllegalStateException("Context payload persistence mismatch");
        }
        return stored;
    }

    public ContextPayloadEntity findVisible(String sessionId, String reference) {
        return lambdaQuery()
                .eq(ContextPayloadEntity::getTenantId, TenantContext.requiredTenantId())
                .eq(ContextPayloadEntity::getConversationSessionId, sessionId)
                .eq(ContextPayloadEntity::getReference, reference)
                .one();
    }
}
