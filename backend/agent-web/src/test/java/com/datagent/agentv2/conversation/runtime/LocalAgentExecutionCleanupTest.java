package com.datagent.agentv2.conversation.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LocalAgentExecutionCleanupTest {
    @Test
    void workerFailureAlwaysReleasesItsAccumulatedAnswer() throws Exception {
        var persisted = mock(AgentExecutionStreamCheckpointService.class);
        when(persisted.findByExecutionId("run"))
                .thenReturn(
                        AgentExecutionStreamCheckpoint.initial(
                                "run", ExecutionPhase.PREPARING_CONTEXT));
        when(persisted.beginModelInvocation(eq("run"), anyLong(), eq(1L))).thenReturn(true);
        var streams = new ExecutionStreamCheckpointCoordinator(persisted);
        var run = new LocalAgentExecution("tenant", "session", "run", 2);
        streams.beginModelInvocation(run, 1, Instant.now());
        var engine = mock(ReactExecutionEngine.class);
        doThrow(new IllegalStateException("provider unavailable")).when(engine).execute(run);
        // 根据当前生产构造器注入真实累计器，其余外部边界使用替身；不在生产类增加测试入口。
        var constructor = LocalAgentExecutionLauncher.class.getDeclaredConstructors()[0];
        Object[] dependencies =
                Arrays.stream(constructor.getParameterTypes())
                        .map(
                                type -> {
                                    if (type == ExecutionStreamCheckpointCoordinator.class) {
                                        return streams;
                                    }
                                    if (type == ReactExecutionEngine.class) {
                                        return engine;
                                    }
                                    return mock(type);
                                })
                        .toArray();
        var launcher = (LocalAgentExecutionLauncher) constructor.newInstance(dependencies);
        ReflectionTestUtils.invokeMethod(launcher, "executeInTenant", run);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(streams, "accumulators")).isEmpty();
    }
}
