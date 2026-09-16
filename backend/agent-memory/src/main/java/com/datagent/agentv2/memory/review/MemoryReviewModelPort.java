package com.datagent.agentv2.memory.review;

/** 模型调用与纯完整输入估算的窄端口，业务 worker 不依赖 web 或主会话 prepare。 */
public interface MemoryReviewModelPort {
    int maxInputTokens();

    int estimate(MemoryReviewInput input);

    /** 缺少配置时也可计算；只返回安全稳定摘要，用于跨重启比较配置是否改变。 */
    String configurationFingerprint();

    MemoryReviewOutput complete(MemoryReviewInput input);
}
