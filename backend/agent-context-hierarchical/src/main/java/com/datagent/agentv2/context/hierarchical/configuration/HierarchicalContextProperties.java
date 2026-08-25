package com.datagent.agentv2.context.hierarchical.configuration;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Hierarchical 上下文压缩的服务级参数。 */
@Getter
@Setter
@Validated
@ConfigurationProperties("data-agent.context.hierarchical")
public class HierarchicalContextProperties {

    /** 标识当前策略快照契约的版本。 */
    private static final int POLICY_VERSION = 2;

    /** 开始压缩前允许使用的上下文窗口比例。 */
    private double triggerRatio = 0.75d;

    /** 压缩完成后期望保留的上下文窗口比例。 */
    private double targetRatio = 0.70d;

    /** 确保压缩触发水位与目标水位关系合法。 */
    @AssertTrue(message = "Hierarchical context ratios and turn windows are invalid")
    public boolean isPolicyValid() {
        return targetRatio > 0.0d && targetRatio < triggerRatio && triggerRatio < 1.0d;
    }

    /** 将当前服务配置冻结为一次 execution 使用的策略快照。 */
    public HierarchicalContextPolicySnapshot snapshot() {
        return new HierarchicalContextPolicySnapshot(POLICY_VERSION, triggerRatio, targetRatio);
    }
}
