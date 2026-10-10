package com.datagent.agentv2.git.model;

/** 正式交付证据要求当前 main 恰好为原合并候选，不能用后继祖先关系代替。 */
public record MergeEvidence(
        /** 原操作固定的合并候选。 */
        String mergeSha,
        /** 当前观察到的main HEAD。 */
        String remoteHeadSha,
        /** 当前main与候选及固定父提交是否完全吻合。 */
        boolean verified) {}
