package com.datagent.agentv2.conversation.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按当前策略解析纯估算接口；raw 等未提供估算器的配置不阻止启动，只在消费时明确拒绝。 */
@Component
public final class ContextInputEstimatorResolver {
    private final Map<String, ContextInputEstimator> estimators;
    private final ContextStrategyProperties properties;

    public ContextInputEstimatorResolver(
            List<ContextInputEstimator> estimators, ContextStrategyProperties properties) {
        Map<String, ContextInputEstimator> indexed = new LinkedHashMap<>();
        for (ContextInputEstimator estimator : estimators) {
            if (indexed.putIfAbsent(estimator.strategyId(), estimator) != null) {
                throw new IllegalStateException(
                        "Duplicate context input estimator: " + estimator.strategyId());
            }
        }
        this.estimators = Map.copyOf(indexed);
        this.properties = properties;
    }

    /** 未配置估算器时调用方必须停止该后台模型流程，不得猜测预算或套用另一策略口径。 */
    public ContextInputEstimator current() {
        ContextInputEstimator selected = estimators.get(properties.getStrategy());
        if (selected == null)
            throw new IllegalStateException(
                    "Context input estimator unavailable for strategy: "
                            + properties.getStrategy());
        return selected;
    }
}
