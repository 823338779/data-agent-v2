package com.datagent.agentv2.memory;

import static org.mockito.Mockito.mock;

import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.persistence.AuditMetaObjectHandler;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapperImpl;
import com.datagent.agentv2.persistence.service.memory.MemoryEntryService;
import com.datagent.agentv2.persistence.service.memory.MemoryEvidenceService;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import com.datagent.agentv2.persistence.service.memory.MemorySuppressionService;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 本模块独立装配真实事务代理和持久化实现；仅尚未接入的对话证据端口使用替身。 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    MybatisPlusConfiguration.class,
    AuditMetaObjectHandler.class,
    MemoryEntryService.class,
    MemoryStateService.class,
    MemoryOperationService.class,
    MemoryEvidenceService.class,
    MemorySuppressionService.class,
    MemoryReviewJobService.class,
    MemoryReviewLeaseService.class,
    MemoryPersistenceMapperImpl.class,
    PrefixedUuidIdGenerator.class,
    MemoryService.class,
    MemoryMetrics.class,
    MemoryWritePolicy.class
})
public class MemoryIntegrationTestConfiguration {
    @Bean
    SimpleMeterRegistry metricRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    MemoryProperties memoryProperties() {
        return new MemoryProperties();
    }

    @Bean
    MemoryContentPolicy contentPolicy(MemoryProperties properties) {
        return new MemoryContentPolicy(properties);
    }

    @Bean
    MemoryEvidencePort evidencePort() {
        return mock(MemoryEvidencePort.class);
    }
}
