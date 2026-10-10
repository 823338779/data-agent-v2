package com.datagent.agentv2.git.model;

/** 远端真实分支及完整 HEAD SHA。 */
public record BranchInfo(
        /** Git短分支名。 */
        String branchName,
        /** 此次远端观察的完整提交。 */
        String headSha) {}
