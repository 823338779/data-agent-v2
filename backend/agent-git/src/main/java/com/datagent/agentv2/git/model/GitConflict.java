package com.datagent.agentv2.git.model;

/** 固定三方冲突材料；正文受文件及总量限制，null 表示该侧不存在文件。 */
public record GitConflict(
        /** 仓库内相对路径。 */
        String path,
        /** 原基线正文。 */
        String baseContent,
        /** 当前草稿正文。 */
        String localContent,
        /** 本次观测远端正文。 */
        String remoteContent) {}
