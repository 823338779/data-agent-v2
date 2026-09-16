package com.datagent.agentv2.git.model;

import java.util.List;

/** 不可变 Git tree 及其完整审阅材料。 */
public record GitSnapshot(
        String treeId, String candidateDigest, String diff, List<GitFileChange> changes) {
    public GitSnapshot {
        changes = List.copyOf(changes);
    }
}
