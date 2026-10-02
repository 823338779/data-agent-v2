package com.datagent.agentv2.conversation.domain;

import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Agent 执行输出的最新完整流检查点。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
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

    /** 从完整存储快照恢复领域状态；Builder 只负责组装，构造时统一校验不变量。 */
    @Builder
    private AgentExecutionStreamCheckpoint(
            String executionId,
            Long modelInvocationSequence,
            ExecutionPhase phase,
            Long outputDeltaSequence,
            String answer,
            Long version) {
        this.executionId = DomainInvariants.text(executionId, "executionId");
        this.modelInvocationSequence =
                DomainInvariants.nonnegative(modelInvocationSequence, "modelInvocationSequence");
        this.phase = java.util.Objects.requireNonNull(phase, "phase");
        this.outputDeltaSequence =
                DomainInvariants.nonnegative(outputDeltaSequence, "outputDeltaSequence");
        this.answer = answer == null ? "" : answer;
        this.version = DomainInvariants.nonnegative(version, "version");
    }

    public static AgentExecutionStreamCheckpoint initial(String executionId, ExecutionPhase phase) {
        return builder()
                .executionId(executionId)
                .modelInvocationSequence(0L)
                .phase(phase)
                .outputDeltaSequence(0L)
                .answer("")
                .version(0L)
                .build();
    }
}
