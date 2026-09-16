package com.datagent.agentv2.memory.review;

/** 模型或纯预算能力未配置；与模型临时失败分离，任务阻塞等待相关配置变化。 */
public final class MemoryReviewConfigurationException extends RuntimeException {
    public MemoryReviewConfigurationException() {
        super("后台模型或输入估算配置不可用");
    }
}
