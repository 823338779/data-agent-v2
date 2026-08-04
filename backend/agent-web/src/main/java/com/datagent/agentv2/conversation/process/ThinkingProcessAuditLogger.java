package com.datagent.agentv2.conversation.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/** 在业务事务提交后输出独立的完整安全 ProcessItem 审计文档。 */
@Component
public final class ThinkingProcessAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("agent.conversation.process.audit");
    private static final Logger LOG = LoggerFactory.getLogger(ThinkingProcessAuditLogger.class);
    private static final String AUDIT_FAILURE_MESSAGE =
            "Conversation process audit logging failed errorType={}";

    private final ObjectMapper json;

    public ThinkingProcessAuditLogger(ObjectMapper json) {
        this.json = json;
    }

    /** 审计设施失败不得反向影响已经提交的业务事务。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(ProcessAuditEvent event) {
        try {
            // 按主管审计要求记录经安全脱敏后的完整 ProcessItem 详情和工具调用结果，便于后续审计排查；不得根据数据库是否截断跳过该日志。
            AUDIT.info("{}", json.writeValueAsString(event.safeFullDocument(json)));
        } catch (RuntimeException failure) {
            LOG.warn(AUDIT_FAILURE_MESSAGE, failure.getClass().getSimpleName());
        }
    }
}
