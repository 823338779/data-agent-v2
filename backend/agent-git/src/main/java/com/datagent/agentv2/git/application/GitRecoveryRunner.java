package com.datagent.agentv2.git.application;

import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;

/** 启用能力后由 server 在启动时调用一次；仅核实遗留已确认记录，不恢复 Agent execution。 */
@RequiredArgsConstructor
public final class GitRecoveryRunner {
    private final GitChangePersistenceService records;
    private final GitSubmissionService submissions;

    public void recover() {
        TenantContext.run(
                TenantContext.TENANT_ID,
                () -> {
                    for (var change : records.unfinished())
                        submissions.reconcile(
                                change.getConversationSessionId(), change.getChangeId());
                });
    }
}
