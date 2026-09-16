package com.datagent.agentv2.memory.config;

import java.time.Duration;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 个人长记忆的容量、注入及后台审查边界；默认值逐项对应已确认规格。 */
@Getter
@ConfigurationProperties("data-agent.memory")
public class MemoryProperties {
    private int personProfileLimit = 800;
    private int collaborationPreferenceLimit = 1500;

    /** 每类注入候选上限，默认 20；可信部署可配置任意正数，renderer 严格使用当前值。 */
    private int injectionPerKindLimit = 20;

    /** 记忆贡献独立 token 预算，默认 1024；可信部署可调整，最终仍受模型完整输入硬限约束。 */
    private int injectionTokenBudget = 1024;

    private int reviewMessageLimit = 40;
    private int reviewCharLimit = 50_000;
    private int maxModelCalls = 8;
    private int maxMaintenanceCalls = 16;
    private Duration reviewDeadline = Duration.ofSeconds(120);
    private Duration reviewDelay = Duration.ofSeconds(30);
    private Duration scanInitialDelay = Duration.ofSeconds(30);
    private Duration scanInterval = Duration.ofSeconds(60);
    private int claimBatchSize = 4;
    private Duration leaseDuration = Duration.ofSeconds(120);
    private Duration retryDelay = Duration.ofMinutes(5);
    private int maxAttempts = 3;

    public void setPersonProfileLimit(int value) {
        personProfileLimit = positive(value, "personProfileLimit");
    }

    public void setCollaborationPreferenceLimit(int value) {
        collaborationPreferenceLimit = positive(value, "collaborationPreferenceLimit");
    }

    public void setInjectionPerKindLimit(int value) {
        injectionPerKindLimit = positive(value, "injectionPerKindLimit");
    }

    public void setInjectionTokenBudget(int value) {
        injectionTokenBudget = positive(value, "injectionTokenBudget");
    }

    public void setReviewMessageLimit(int value) {
        reviewMessageLimit = positive(value, "reviewMessageLimit");
    }

    public void setReviewCharLimit(int value) {
        reviewCharLimit = positive(value, "reviewCharLimit");
    }

    public void setMaxModelCalls(int value) {
        maxModelCalls = positive(value, "maxModelCalls");
    }

    public void setMaxMaintenanceCalls(int value) {
        maxMaintenanceCalls = positive(value, "maxMaintenanceCalls");
    }

    public void setReviewDeadline(Duration value) {
        reviewDeadline = positive(value, "reviewDeadline");
    }

    public void setReviewDelay(Duration value) {
        reviewDelay = nonNegative(value, "reviewDelay");
    }

    public void setScanInitialDelay(Duration value) {
        scanInitialDelay = nonNegative(value, "scanInitialDelay");
    }

    public void setScanInterval(Duration value) {
        scanInterval = positive(value, "scanInterval");
    }

    public void setClaimBatchSize(int value) {
        claimBatchSize = positive(value, "claimBatchSize");
    }

    public void setLeaseDuration(Duration value) {
        leaseDuration = positive(value, "leaseDuration");
    }

    public void setRetryDelay(Duration value) {
        retryDelay = nonNegative(value, "retryDelay");
    }

    public void setMaxAttempts(int value) {
        maxAttempts = positive(value, "maxAttempts");
    }

    private static int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正数");
        }
        return value;
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " 必须为正数");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(field + " 不得为负数");
        }
        return value;
    }
}
