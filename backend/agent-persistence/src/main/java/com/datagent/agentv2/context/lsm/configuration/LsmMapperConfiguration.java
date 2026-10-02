package com.datagent.agentv2.context.lsm.configuration;

import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.mapping.LsmPersistenceStructMapper;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 集中注册 LSM 存储服务；raw 与其他策略不扫描此数据库 Mapper 包。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
@ComponentScan(basePackageClasses = LsmPersistenceStructMapper.class)
@Import({
    LsmContextManifestRepository.class,
    LsmSummarySegmentRepository.class,
    LsmDocumentCodec.class
})
@MapperScan(
        basePackages = "com.datagent.agentv2.context.lsm.persistence.mapper",
        annotationClass = Mapper.class)
public class LsmMapperConfiguration {}
