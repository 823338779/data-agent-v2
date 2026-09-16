package com.datagent.agentv2.server.memory;

import com.datagent.agentv2.memory.MemoryContentPolicy;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.context.MemoryContextRenderer;
import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.memory.tool.MemoryToolDispatcher;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.ObjectMapper;

/** 只装配初始化和有界运行时资源，后台业务规则及调度分别留在 memory/web。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryRuntimeConfiguration {
    @Bean
    @Order(1)
    ApplicationRunner initializeMemory(MemoryService memory) {
        return args -> memory.initialize();
    }

    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor memoryReviewExecutor() {
        // 无等待队列：provider 不响应 interrupt 时仍占四个固定槽位之一，不继续新建替代线程。
        return new ThreadPoolExecutor(
                4,
                4,
                0,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("memory-review-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService memoryReviewHeartbeatExecutor() {
        var executor =
                new ScheduledThreadPoolExecutor(
                        2, Thread.ofPlatform().daemon().name("memory-guard-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService memoryReviewScanExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("memory-scan-", 0).factory());
    }

    @Bean
    MemoryReviewWorker memoryReviewWorker(
            MemoryProperties properties,
            MemoryReviewCoordinator coordinator,
            MemoryService memory,
            MemoryReviewHistoryPort history,
            MemoryReviewModelPort model,
            MemoryToolDispatcher dispatcher,
            MemoryOperationService operations,
            MemoryContentPolicy content,
            ObjectMapper json,
            MemoryMetrics metrics,
            @Qualifier("memoryReviewHeartbeatExecutor") ScheduledExecutorService guards) {
        return new MemoryReviewWorker(
                properties,
                coordinator,
                memory,
                history,
                model,
                dispatcher,
                operations,
                content,
                json,
                guards,
                metrics);
    }

    @Bean
    MemoryContextRenderer memoryContextRenderer(MemoryProperties properties) {
        return new MemoryContextRenderer(properties);
    }

    @Bean
    MemoryContentPolicy memoryContentPolicy(MemoryProperties properties) {
        return new MemoryContentPolicy(properties);
    }
}
