package com.datagent.agentv2.context.hierarchical.configuration;

import com.datagent.agentv2.context.hierarchical.persistence.mapping.HierarchicalPersistenceMapper;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** 存储装配留在 persistence，只有选中 hierarchical 时注册数据库访问服务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@ComponentScan(basePackageClasses = HierarchicalPersistenceMapper.class)
@MapperScan(
        basePackages = "com.datagent.agentv2.context.hierarchical.persistence.mapper",
        annotationClass = Mapper.class)
public class HierarchicalMapperConfiguration {
    @Bean
    com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository
            contextPayloadRepository() {
        return new com.datagent.agentv2.context.hierarchical.persistence.repository
                .ContextPayloadRepository();
    }

    @Bean
    HierarchicalCheckpointRepository hierarchicalCheckpointRepository(
            HierarchicalPersistenceMapper models) {
        return new HierarchicalCheckpointRepository(models);
    }

    @Bean
    HierarchicalColdRepository hierarchicalColdRepository(HierarchicalPersistenceMapper models) {
        return new HierarchicalColdRepository(models);
    }
}
