package com.datagent.agentv2.conversation.context;

/** 上下文插件在不依赖 Web runtime 的前提下验证本机执行边界。 */
public interface ContextExecutionControlPort {

    void assertRunningAndWithinDeadline(ContextExecutionRef execution);
}
