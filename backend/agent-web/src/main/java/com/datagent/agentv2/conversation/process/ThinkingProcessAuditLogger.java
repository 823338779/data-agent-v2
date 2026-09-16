package com.datagent.agentv2.conversation.process;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/** 事务提交后输出安全审计投影；个人记忆只保留关联标识、有限状态和耗时。 */
@Component
@RequiredArgsConstructor
public final class ThinkingProcessAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("agent.conversation.process.audit");
    private static final Logger LOG = LoggerFactory.getLogger(ThinkingProcessAuditLogger.class);
    private static final String AUDIT_FAILURE_MESSAGE =
            "Conversation process audit logging failed errorType={}";

    private final ObjectMapper json;

    /** 审计设施失败不得反向影响已经提交的业务事务。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(ProcessAuditEvent event) {
        try {
            // 普通工具保留既有安全完整审计；memory 使用专属白名单，数据库截断与否均不输出个人正文。
            AUDIT.info("{}", json.writeValueAsString(event.safeFullDocument(json)));
        } catch (RuntimeException failure) {
            LOG.warn(AUDIT_FAILURE_MESSAGE, failure.getClass().getSimpleName());
        }
    }
}
