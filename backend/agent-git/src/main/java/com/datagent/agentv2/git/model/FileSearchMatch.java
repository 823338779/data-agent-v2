package com.datagent.agentv2.git.model;

/** 文本搜索命中位置，行号从 1 开始。 */
public record FileSearchMatch(String path, int line, String text) {}
