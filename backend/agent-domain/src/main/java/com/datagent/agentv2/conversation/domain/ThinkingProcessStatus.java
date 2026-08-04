package com.datagent.agentv2.conversation.domain;

/** 回答生成过程节点的持久化生命周期状态。 */
public enum ThinkingProcessStatus {
    /** 工具已经由模型选择，但尚未真正开始执行。 */
    PREPARED,

    /** 节点正在处理。 */
    RUNNING,

    /** 节点已经成功完成。 */
    SUCCEEDED,

    /** 节点已经失败收敛。 */
    FAILED
}
