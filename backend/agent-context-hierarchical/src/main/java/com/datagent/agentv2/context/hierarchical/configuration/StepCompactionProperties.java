package com.datagent.agentv2.context.hierarchical.configuration;

import com.datagent.agentv2.conversation.context.ContextBudget;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** V3 参数使用独立前缀，绝不把 V2 的窗口比例静默解释为可用输入比例。 */
@Getter
@Setter
@Validated
@ConfigurationProperties("data-agent.context.hierarchical.step-compaction")
public class StepCompactionProperties {
    /** 占硬输入预算的触发比例。 */
    private double triggerRatio = 0.70d;

    /** 占硬输入预算的期望压缩后比例。 */
    private double targetRatio = 0.55d;

    /** 尽量保留的近期完整组数；不是硬保护窗口。 */
    private int retainedCompletedGroups = 2;

    /** 单份安全原文持久化上限，UTF-8 字节。 */
    private int payloadMaxBytes = 1048576;

    /** 单页读取上限，UTF-8 字节。 */
    private int resultPageMaxBytes = 4096;

    @AssertTrue(message = "Invalid step compaction budgets")
    public boolean isValid() {
        return targetRatio > 0
                && targetRatio < triggerRatio
                && triggerRatio < 1
                && retainedCompletedGroups >= 0
                && payloadMaxBytes > 0
                && resultPageMaxBytes > 0;
    }

    public int triggerTokens(ContextBudget budget) {
        validate();
        return (int) Math.floor(budget.hardInputLimitTokens() * triggerRatio);
    }

    public int targetTokens(ContextBudget budget) {
        validate();
        return (int) Math.floor(budget.hardInputLimitTokens() * targetRatio);
    }

    public void validate() {
        if (!isValid()) {
            throw new IllegalArgumentException("Invalid step compaction budgets");
        }
    }
}
