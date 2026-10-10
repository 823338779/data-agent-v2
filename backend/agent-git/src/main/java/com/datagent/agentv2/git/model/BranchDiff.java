package com.datagent.agentv2.git.model;

/** 交付确认覆盖当前共享分支的完整差异；两端 SHA 与正文一起固定。 */
public record BranchDiff(
        /** 共享开发分支的完整提交。 */
        String sourceSha,
        /** main的完整提交。 */
        String mainSha,
        /** 两端固定后取得的完整差异正文。 */
        String diff) {}
