package com.datagent.agentv2.artifact.application;

/** 正式发布后供 Tool 返回的稳定业务标识。 */
public record ArtifactPublicationResult(String artifactId, String uiBlockId) {}
