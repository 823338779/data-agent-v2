package com.datagent.agentv2.artifact.application;

/** 对外消息投影中的受控 UI Block。 */
public record MessageUiBlockProjection(
        String blockId,
        String type,
        int schemaVersion,
        String variant,
        Object payload,
        int displayOrder) {}
