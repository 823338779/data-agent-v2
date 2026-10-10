package com.datagent.agentv2.git.model;

/** 固定合并两端、候选及对象所在目录；push 前由应用层持久化候选 SHA。 */
public record MergeCandidate(
        /** 本次合并的受控仓库。 */
        String repository,
        /** 来源开发分支短名。 */
        String branchName,
        /** 固定来源提交。 */
        String sourceSha,
        /** 固定main提交。 */
        String mainSha,
        /** 包含两端父提交的唯一候选。 */
        String candidateSha,
        /** 保存候选Git对象的本地目录。 */
        java.nio.file.Path workspace) {}
