package com.datagent.agentv2.conversation.domain;

import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent 执行输出的最新完整流检查点。 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentExecutionStreamCheckpoint {

    /** 检查点所属执行业务标识，每个执行最多一个有效检查点。 */
    private String executionId;

    /** 执行内当前模型调用序号，0 表示尚未调用模型。 */
    private Long modelInvocationSequence;

    /** 当前执行阶段，用于进度展示和断线恢复。 */
    private ExecutionPhase phase;

    /** 已持久化的最高输出增量序号，0 表示尚无输出。 */
    private Long outputDeltaSequence;

    /** 截至当前增量拼接出的完整用户可见回答。 */
    private String answer;

    /** 快照并发更新使用的乐观锁版本。 */
    private Long version;

    public static AgentExecutionStreamCheckpoint initial(String executionId, ExecutionPhase phase) {
        AgentExecutionStreamCheckpoint checkpoint = new AgentExecutionStreamCheckpoint();
        checkpoint.executionId = executionId;
        checkpoint.modelInvocationSequence = 0L;
        checkpoint.phase = java.util.Objects.requireNonNull(phase, "phase");
        checkpoint.outputDeltaSequence = 0L;
        checkpoint.answer = "";
        checkpoint.version = 0L;
        return checkpoint;
    }
}
