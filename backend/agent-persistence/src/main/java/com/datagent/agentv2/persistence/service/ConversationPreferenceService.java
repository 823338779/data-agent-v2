package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.entity.ConversationPreferenceEntity;
import com.datagent.agentv2.persistence.service.mapper.ConversationPreferenceMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationPreferenceService {

    private final ConversationPreferenceMapper mapper;

    /** 新用户首次使用会话列表时建立默认版本。 */
    public void ensure(String userId) {
        mapper.insertIfMissing(TenantContext.requiredTenantId(), userId);
    }

    /** 在事务中锁定用户偏好。 */
    public ConversationPreferenceEntity lockByUserId(String userId) {
        return mapper.selectByUserIdForUpdate(TenantContext.requiredTenantId(), userId);
    }

    /** 查询用户偏好的当前公开版本。 */
    public ConversationPreferenceEntity findByUserId(String userId) {
        return mapper.selectByUserId(TenantContext.requiredTenantId(), userId);
    }

    /** 比较并递增用户排序版本。 */
    public boolean incrementOrderingVersion(String userId, long expectedOrderingVersion) {
        return mapper.incrementOrderingVersion(
                        TenantContext.requiredTenantId(), userId, expectedOrderingVersion)
                == 1;
    }
}
