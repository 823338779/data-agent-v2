package com.datagent.agentv2.conversation.cluster;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.NOTIFICATION_EXECUTION_CANCELLATION_REQUESTED;

import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import com.datagent.agentv2.persistence.service.NotificationOutboxMessageService;
import com.datagent.agentv2.persistence.service.TenantService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 扫描取消 Outbox 并发布低延迟唤醒；失败只更新独立重试状态。 */
@Component
public final class ExecutionCancellationOutboxPublisher {

    /** 分发跨实例执行取消请求的 Redis Pub/Sub 通道。 */
    public static final String EXECUTION_CANCELLATION_CHANNEL = "agent:execution-cancellation";

    private static final int BATCH_SIZE = 64;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(250);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final NotificationOutboxMessageService outbox;
    private final TenantService tenants;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentExecutionRuntimeProperties runtime;

    public ExecutionCancellationOutboxPublisher(
            NotificationOutboxMessageService outbox,
            TenantService tenants,
            StringRedisTemplate redis,
            ObjectMapper json,
            AgentExecutionRuntimeProperties runtime) {
        this.outbox = outbox;
        this.tenants = tenants;
        this.redis = redis;
        this.json = json;
        this.runtime = runtime;
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.control-outbox-poll-interval:250ms}")
    public void publishDue() {
        if (runtime.schedulingEnabled()) {
            publishDueNow();
        }
    }

    public int publishDueNow() {
        int published = 0;
        for (String tenantId : tenants.activeTenantIds()) {
            published += TenantContext.call(tenantId, this::publishTenantDue);
        }
        return published;
    }

    private int publishTenantDue() {
        Instant now = Instant.now();
        int published = 0;
        for (NotificationOutboxMessageEntity notification :
                outbox.due(NOTIFICATION_EXECUTION_CANCELLATION_REQUESTED, now, BATCH_SIZE)) {
            try {
                AgentExecutionCancellationSignal envelope =
                        json.readValue(
                                notification.getPayload(), AgentExecutionCancellationSignal.class);
                redis.convertAndSend(
                        EXECUTION_CANCELLATION_CHANNEL, json.writeValueAsString(envelope));
                if (outbox.markPublished(
                        notification.getNotificationMessageId(), notification.getVersion(), now)) {
                    published++;
                }
            } catch (Exception failure) {
                Instant nextAttempt = now.plus(backoff(notification.getAttemptCount()));
                outbox.markFailed(
                        notification.getNotificationMessageId(),
                        notification.getVersion(),
                        nextAttempt,
                        error(failure),
                        now);
            }
        }
        return published;
    }

    private static Duration backoff(Integer attemptCount) {
        int exponent = Math.min(attemptCount == null ? 0 : attemptCount, 16);
        long delay = INITIAL_BACKOFF.toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(delay, MAX_BACKOFF.toMillis()));
    }

    private static String error(Exception failure) {
        String message = failure.getMessage();
        String value = message == null ? failure.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(value.length(), MAX_ERROR_LENGTH));
    }
}
