package com.datagent.agentv2.git.port;

import com.datagent.agentv2.git.model.GitDraftSnapshot;
import com.datagent.agentv2.git.model.GitValidationEvidence;

/** 候选统一边界的本地证据检查；持有工作区锁时禁止调用远端。 */
@FunctionalInterface
public interface GitCandidateValidation {
    GitValidationEvidence validate(String sessionId, GitDraftSnapshot snapshot);
}
