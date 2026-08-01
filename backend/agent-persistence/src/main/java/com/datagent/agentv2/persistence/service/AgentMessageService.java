package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentMessageMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageService extends ServiceImpl<AgentMessageMapper, AgentMessageEntity> {

    private static final String VISIBILITY_USER = "user";

    /** 对外会话投影只返回 user visibility 消息，并保持稳定序号。 */
    public List<AgentMessageEntity> findUserVisibleBySessionId(String sessionId) {
        return lambdaQuery()
                .eq(AgentMessageEntity::getSessionId, sessionId)
                .eq(AgentMessageEntity::getVisibility, VISIBILITY_USER)
                .orderByAsc(AgentMessageEntity::getSequence)
                .list();
    }

    /** 模型上下文读取完整消息日志，不过滤 internal 消息。 */
    public List<AgentMessageEntity> findContextByRunIdUpTo(String runId, long sequence) {
        return lambdaQuery()
                .eq(AgentMessageEntity::getRunId, runId)
                .le(AgentMessageEntity::getSequence, sequence)
                .orderByAsc(AgentMessageEntity::getSequence)
                .list();
    }

    /** 按业务标识读取单条消息，供 execution 与 assistant call 建立一致性边界。 */
    public AgentMessageEntity findByMessageId(String messageId) {
        return lambdaQuery().eq(AgentMessageEntity::getMessageId, messageId).one();
    }
}
