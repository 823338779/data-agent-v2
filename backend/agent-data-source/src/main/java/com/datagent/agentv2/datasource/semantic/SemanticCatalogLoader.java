package com.datagent.agentv2.datasource.semantic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import org.springframework.core.io.Resource;

/** 从受信任服务端资源读取 YAML，但仍拒绝未知字段和畸形文档。 */
public final class SemanticCatalogLoader {

    private final YAMLMapper mapper;

    public SemanticCatalogLoader() {
        this.mapper = new YAMLMapper();
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public SemanticCatalogDocument load(Resource resource) {
        try (var input = resource.getInputStream()) {
            return mapper.readValue(input, SemanticCatalogDocument.class);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 StarRocks 语义配置", failure);
        }
    }
}
