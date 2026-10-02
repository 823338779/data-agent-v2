package com.datagent.agentv2.server.stream;

import com.datagent.agentv2.conversation.stream.ConversationEventStreamFacade;
import com.datagent.agentv2.conversation.summary.ConversationSummaryStreamFacade;
import com.datagent.agentv2.diagnostics.SafeFailures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 只装配 SSE 对账的周期与线程生命周期，快照和缺口恢复业务仍由 web 门面负责。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ConversationStreamRuntimeConfiguration {
    private static final long RECONCILIATION_INTERVAL_MILLIS = 1_000L;
    private final ConversationEventStreamFacade detailStreams;
    private final ConversationSummaryStreamFacade summaryStreams;

    @Bean
    ThreadPoolTaskScheduler conversationStreamScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("conversation-stream-reconcile-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Scheduled(
            fixedDelay = RECONCILIATION_INTERVAL_MILLIS,
            scheduler = "conversationStreamScheduler")
    public void reconcileDetails() {
        try {
            detailStreams.reconcileActiveSessions();
        } catch (RuntimeException failure) {
            // 数据库短暂故障不能取消后续周期，也不能把异常中的用户正文写入日志。
            log.warn("详细会话流对账失败", SafeFailures.sanitized(failure));
        }
    }

    @Scheduled(
            fixedDelay = RECONCILIATION_INTERVAL_MILLIS,
            scheduler = "conversationStreamScheduler")
    public void reconcileSummaries() {
        try {
            summaryStreams.reconcileSubscribers();
        } catch (RuntimeException failure) {
            log.warn("会话摘要流对账失败", SafeFailures.sanitized(failure));
        }
    }
}
