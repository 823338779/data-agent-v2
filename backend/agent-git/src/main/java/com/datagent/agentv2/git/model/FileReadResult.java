package com.datagent.agentv2.git.model;

/** 完整 UTF-8 文件内容和用于后续写入冲突检查的摘要。 */
public record FileReadResult(String path, String content, String sha256) {}
