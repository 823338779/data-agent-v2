package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.workflow.port.WorkflowEventPort;
import com.datagent.agentv2.workflow.runtime.WorkflowEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 将 Workflow 端口事件桥接为同 JVM Spring 事件，不执行持久化。 */
@Component
@RequiredArgsConstructor
public final class WorkflowSseEventPublisher implements WorkflowEventPort {

    private final ApplicationEventPublisher applicationEvents;

    @Override
    public void publish(WorkflowEvent event) {
        applicationEvents.publishEvent(new WorkflowEventPublished(event));
    }

    /** 事件本身只携带已经脱敏的公开 Workflow 载荷。 */
    public record WorkflowEventPublished(WorkflowEvent event) {}
}
