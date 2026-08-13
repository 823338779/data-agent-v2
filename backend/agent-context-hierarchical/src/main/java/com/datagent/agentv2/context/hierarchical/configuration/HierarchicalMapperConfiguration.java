package com.datagent.agentv2.context.hierarchical.configuration;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

/** 将 MyBatis 扫描严格限制在 hierarchical 插件私有 Mapper 包。 */
@MapperScan(
        basePackages = "com.datagent.agentv2.context.hierarchical.persistence.mapper",
        annotationClass = Mapper.class)
public class HierarchicalMapperConfiguration {}
