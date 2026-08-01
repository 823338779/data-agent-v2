package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.INSTANCE_ID_PREFIX;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** 标识当前服务进程中的执行工作实例，作为数据库 lease owner 和 fencing 参与者。 */
@Component
public final class ExecutionWorkerIdentity {

    private static final String BLANK_INSTANCE_ID_MESSAGE = "instance id must not be blank";

    /** 当前执行工作实例参与 lease owner 判定时使用的唯一标识。 */
    private final String workerInstanceId;

    public ExecutionWorkerIdentity() {
        this(INSTANCE_ID_PREFIX + UUID.randomUUID());
    }

    public ExecutionWorkerIdentity(String workerInstanceId) {
        if (workerInstanceId == null || workerInstanceId.isBlank()) {
            throw new IllegalArgumentException(BLANK_INSTANCE_ID_MESSAGE);
        }
        this.workerInstanceId = workerInstanceId;
    }

    /** 返回当前执行工作实例的唯一标识。 */
    public String workerInstanceId() {
        return workerInstanceId;
    }
}
