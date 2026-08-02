package com.datagent.agentv2.conversation.cluster;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_RUNNING;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnerRegistry;
import com.datagent.agentv2.conversation.runtime.ExecutionWorkerIdentity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 所有实例监听取消唤醒，但仅数据库当前 owner 可以处理中止。 */
@Component
public final class RedisExecutionCancellationListener implements AutoCloseable {

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionLeaseService leases;
    private final AgentExecutionPort execution;
    private final ExecutionOwnerRegistry owners;
    private final ExecutionWorkerIdentity identity;
    private final ObjectMapper json;
    private final RedisMessageListenerContainer container;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public RedisExecutionCancellationListener(
            AgentExecutionService agentExecutions,
            AgentExecutionLeaseService leases,
            AgentExecutionPort execution,
            ExecutionOwnerRegistry owners,
            ExecutionWorkerIdentity identity,
            ObjectMapper json,
            RedisConnectionFactory connections) {
        this.agentExecutions = agentExecutions;
        this.leases = leases;
        this.execution = execution;
        this.owners = owners;
        this.identity = identity;
        this.json = json;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connections);
    }

    @PostConstruct
    public void start() {
        container.afterPropertiesSet();
        container.addMessageListener(
                (message, ignored) -> receive(message.getBody()),
                new ChannelTopic(
                        ExecutionCancellationOutboxPublisher.EXECUTION_CANCELLATION_CHANNEL));
        container.start();
    }

    public boolean handle(AgentExecutionCancellationSignal envelope) {
        String idempotencyKey = envelope.notificationMessageId() + ":" + envelope.controlVersion();
        if (!identity.workerInstanceId().equals(envelope.ownerInstanceId())
                || !processed.add(idempotencyKey)) {
            return false;
        }
        return TenantContext.call(envelope.tenantId(), () -> handleOwned(envelope));
    }

    private boolean handleOwned(AgentExecutionCancellationSignal envelope) {
        AgentExecution current = agentExecutions.findByExecutionId(envelope.executionId());
        if (current == null
                || !STATUS_RUNNING.equals(current.getStatus())
                || !identity.workerInstanceId().equals(current.getOwnerInstanceId())
                || current.getExecutionAttemptNumber() != envelope.executionAttemptNumber()
                || current.getVersion() != envelope.controlVersion()
                || current.getCancellationRequestedAt() == null) {
            return false;
        }
        AgentExecutionPort.ClaimedAgentExecution owned =
                new AgentExecutionPort.ClaimedAgentExecution(
                        envelope.tenantId(),
                        envelope.executionId(),
                        envelope.ownerInstanceId(),
                        envelope.executionAttemptNumber());
        if (!leases.cancelIfRequested(owned)) {
            return false;
        }
        try {
            execution.cancel(owned);
        } finally {
            owners.release(owned.executionId());
        }
        return true;
    }

    private void receive(byte[] body) {
        try {
            AgentExecutionCancellationSignal envelope =
                    json.readValue(
                            new String(body, StandardCharsets.UTF_8),
                            AgentExecutionCancellationSignal.class);
            handle(envelope);
        } catch (Exception ignored) {
            // 非法消息不影响数据库轮询兜底。
        }
    }

    @PreDestroy
    @Override
    public void close() {
        container.stop();
        try {
            container.destroy();
        } catch (Exception ignored) {
            // stop 已结束订阅，销毁异常不再影响运行时。
        }
    }
}
