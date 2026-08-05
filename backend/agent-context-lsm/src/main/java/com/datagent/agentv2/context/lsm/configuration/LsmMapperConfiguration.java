package com.datagent.agentv2.context.lsm.configuration;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 将插件专属 Mapper 限制在插件包中，避免扩展现有持久化模块的扫描范围。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(
        basePackages = "com.datagent.agentv2.context.lsm.persistence.mapper",
        annotationClass = Mapper.class)
public class LsmMapperConfiguration {}
