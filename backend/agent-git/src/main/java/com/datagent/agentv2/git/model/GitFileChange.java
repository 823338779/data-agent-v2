package com.datagent.agentv2.git.model;

/** 冻结候选中的单文件变更；status 使用 Git name-status 的 A、M、D 等稳定状态码。 */
public record GitFileChange(String status, String path) {}
