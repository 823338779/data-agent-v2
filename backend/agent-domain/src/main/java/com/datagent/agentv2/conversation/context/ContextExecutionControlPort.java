package com.datagent.agentv2.conversation.context;

import java.util.Map;
import java.util.Objects;

/** 插件在不依赖 Web runtime 的前提下执行 fencing、取消、事件和失败收敛。 */
public interface ContextExecutionControlPort {

    void assertOwnedAndWithinBudget(ContextExecutionRef execution);

    boolean cancelIfRequested(ContextExecutionRef execution);

    void publish(ContextExecutionRef execution, ContextLifecycleEvent event);

    void fail(ContextExecutionRef execution, String failureCode, String safeMessage);

    /** 不携带正文的上下文生命周期事件。 */
    record ContextLifecycleEvent(Type type, Map<String, Object> metadata) {

        public ContextLifecycleEvent {
            Objects.requireNonNull(type, "type");
            metadata = Map.copyOf(metadata);
        }

        public enum Type {
            STARTED,
            COMPLETED,
            FAILED
        }
    }
}
