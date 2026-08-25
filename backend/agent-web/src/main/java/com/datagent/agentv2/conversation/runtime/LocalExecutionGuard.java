package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在模型调用和压缩等昂贵步骤之间重新校验执行状态与硬截止时间。 */
@Service
@RequiredArgsConstructor
public class LocalExecutionGuard {

    private final AgentExecutionService executions;

    @Transactional(readOnly = true)
    public void assertRunningAndWithinDeadline(LocalAgentExecution run) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        // 状态和截止时间必须在同一次读取结果上判断；任一条件不满足都禁止继续产生副作用。
        boolean running =
                execution != null
                        && execution.getStatus() == ExecutionStatus.RUNNING
                        && execution.getHardDeadlineAt() != null
                        && execution.getHardDeadlineAt().isAfter(Instant.now());
        if (!running) {
            throw new ExecutionStoppedException(run.executionId());
        }
    }
}
