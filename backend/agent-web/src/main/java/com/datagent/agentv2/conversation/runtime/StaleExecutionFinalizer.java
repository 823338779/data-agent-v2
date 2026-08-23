package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.persistence.service.AgentExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 单实例重启后，把旧进程遗留的运行中记录统一收敛为失败。
 *
 * <p>本地虚拟线程无法跨进程恢复，继续保留 running 会永久阻塞会话的唯一活动执行约束。这里明确失败而不是重放，以避免模型或工具副作用被重复执行。
 */
@Component
@RequiredArgsConstructor
public final class StaleExecutionFinalizer implements ApplicationRunner {

    private static final String FAILURE_CODE = "PROCESS_RESTARTED";
    private static final String FAILURE_DETAIL = "execution stopped because the process restarted";

    private final AgentExecutionService executions;

    @Override
    public void run(ApplicationArguments args) {
        // 启动阶段尚未接收新请求，因此可以一次性扫描全部遗留运行记录。
        executions.findRunning(Integer.MAX_VALUE).forEach(this::fail);
    }

    private void fail(com.datagent.agentv2.conversation.domain.AgentExecution execution) {
        executions.failRunning(
                execution.getExecutionId(), execution.getVersion(), FAILURE_CODE, FAILURE_DETAIL);
    }
}
