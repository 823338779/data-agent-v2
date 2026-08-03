package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent 执行输出的最新完整流检查点。 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentExecutionStreamCheckpoint {

    /** 执行流检查点业务唯一标识。 */
    private String executionStreamCheckpointId;

    /** 检查点所属 Agent 执行标识。 */
    private String executionId;

    /** 当前 owner 的执行尝试编号。 */
    private Long executionAttemptNumber;

    /** 当前模型调用序号。 */
    private Long modelInvocationSequence;

    /** 当前流阶段。 */
    private String phase;

    /** 当前 attempt 内最后发布的 delta 序号。 */
    private Long outputDeltaSequence;

    /** 当前累计回答草稿。 */
    private String answer;

    /** 最近一次完整流投影持久化时间。 */
    private Instant checkpointedAt;

    /** 检查点乐观锁版本。 */
    private Long version;

    /** 组装 queued 执行的初始空检查点。 */
    public static AgentExecutionStreamCheckpoint initial(
            String checkpointId, String executionId, String phase, String emptyAnswer) {
        AgentExecutionStreamCheckpoint checkpoint = new AgentExecutionStreamCheckpoint();
        checkpoint.executionStreamCheckpointId = checkpointId;
        checkpoint.executionId = executionId;
        checkpoint.executionAttemptNumber = 0L;
        checkpoint.modelInvocationSequence = 0L;
        checkpoint.phase = phase;
        checkpoint.outputDeltaSequence = 0L;
        checkpoint.answer = emptyAnswer;
        checkpoint.version = 0L;
        return checkpoint;
    }
}
