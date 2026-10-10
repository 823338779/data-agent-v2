package com.datagent.agentv2.git.model;

/** 候选在当前远端历史中可达才是已保存；remoteHeadSha 仅是观测。 */
public record PushEvidence(
        /** 原操作固定的push候选。 */
        String candidateSha,
        /** 当前观察到的远端HEAD。 */
        String remoteHeadSha,
        /** 远端历史是否证明已接收候选。 */
        boolean verified) {}
