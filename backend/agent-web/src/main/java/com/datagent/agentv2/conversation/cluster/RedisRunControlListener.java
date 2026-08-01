package com.datagent.agentv2.conversation.cluster;

import static com.datagent.agentv2.conversation.RunConstants.STATUS_RUNNING;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunInstanceIdentity;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.conversation.runtime.RunOwnerRegistry;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.service.AgentRunService;
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
public final class RedisRunControlListener implements AutoCloseable {

    private final AgentRunService runs;
    private final RunLeaseService leases;
    private final RunExecutionPort execution;
    private final RunOwnerRegistry owners;
    private final RunInstanceIdentity identity;
    private final ObjectMapper json;
    private final RedisMessageListenerContainer container;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public RedisRunControlListener(
            AgentRunService runs,
            RunLeaseService leases,
            RunExecutionPort execution,
            RunOwnerRegistry owners,
            RunInstanceIdentity identity,
            ObjectMapper json,
            RedisConnectionFactory connections) {
        this.runs = runs;
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
                new ChannelTopic(RunControlOutboxPublisher.RUN_CONTROL_CHANNEL));
        container.start();
    }

    public boolean handle(RunControlEnvelope envelope) {
        String idempotencyKey = envelope.notificationId() + ":" + envelope.controlVersion();
        if (!identity.id().equals(envelope.ownerInstanceId())
                || !processed.add(idempotencyKey)) {
            return false;
        }
        return TenantContext.call(envelope.tenantId(), () -> handleOwned(envelope));
    }

    private boolean handleOwned(RunControlEnvelope envelope) {
        AgentRunEntity current = runs.findByRunId(envelope.runId());
        if (current == null
                || !STATUS_RUNNING.equals(current.getStatus())
                || !identity.id().equals(current.getOwnerInstanceId())
                || current.getExecutionAttempt() != envelope.executionAttempt()
                || current.getVersion() != envelope.controlVersion()
                || current.getCancellationRequestedAt() == null) {
            return false;
        }
        RunExecutionPort.OwnedRun owned =
                new RunExecutionPort.OwnedRun(
                        envelope.tenantId(),
                        envelope.runId(),
                        envelope.ownerInstanceId(),
                        envelope.executionAttempt());
        if (!leases.cancelIfRequested(owned)) {
            return false;
        }
        try {
            execution.cancel(owned);
        } finally {
            owners.release(owned.runId());
        }
        return true;
    }

    private void receive(byte[] body) {
        try {
            RunControlEnvelope envelope =
                    json.readValue(new String(body, StandardCharsets.UTF_8), RunControlEnvelope.class);
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
