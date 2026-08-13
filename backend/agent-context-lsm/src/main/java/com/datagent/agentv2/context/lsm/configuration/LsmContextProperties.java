package com.datagent.agentv2.context.lsm.configuration;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** LSM 上下文压缩的服务级参数；执行开始后会固化为执行级策略快照。 */
@Validated
@ConfigurationProperties("data-agent.context.compaction")
public class LsmContextProperties {

    /** 同层普通节点触发合并所需的最小节点数。 */
    @Min(3)
    @Max(10)
    private int fanout = 5;

    /** LSM 摘要树允许达到的顶层级别。 */
    @Min(1)
    @Max(8)
    private int maxLevel = 4;

    public int getFanout() {
        return fanout;
    }

    public void setFanout(int fanout) {
        this.fanout = fanout;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    /** 将当前服务配置转换为首次 execution 使用的兼容策略快照。 */
    public LsmContextPolicySnapshot snapshot() {
        return LsmContextPolicySnapshot.defaults(fanout, maxLevel);
    }
}
