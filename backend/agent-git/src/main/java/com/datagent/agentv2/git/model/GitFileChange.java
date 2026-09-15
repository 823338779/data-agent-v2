package com.datagent.agentv2.git.model;

/** 冻结候选中的单文件变更。 */
public record GitFileChange(String status, String path) {}
