package com.datagent.agentv2.conversation.domain;

/** 回答生成过程节点的稳定类型。 */
public enum ThinkingProcessType {
    /** 模型产生的可展示思考片段。 */
    THINKING,

    /** 一次完整工具调用及其结果。 */
    TOOL
}
