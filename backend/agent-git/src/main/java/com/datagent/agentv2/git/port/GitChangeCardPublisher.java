package com.datagent.agentv2.git.port;

import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;

/** Web 实现必须以一个短事务校验 execution/message 归属、设置 messageId、调用 register 并登记 UI Block。 */
@FunctionalInterface
public interface GitChangeCardPublisher {
    void publish(String executionId, GitChangeEntity candidate);
}
