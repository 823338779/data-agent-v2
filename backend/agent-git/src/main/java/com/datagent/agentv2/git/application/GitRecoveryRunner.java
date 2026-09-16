package com.datagent.agentv2.git.application;

import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;

/** 启用能力后由 server 在启动时调用一次；仅核实遗留已确认记录，不恢复 Agent execution。 */
@RequiredArgsConstructor
public final class GitRecoveryRunner {
    private final GitChangePersistenceService records;
    private final GitSubmissionService submissions;

    /** 就绪前只做一次短查询并冻结 ID；返回的任务仅核实这批遗留记录，不能选入随后新确认的提交。 */
    public Runnable captureRecovery() {
        var targets =
                TenantContext.call(
                        TenantContext.TENANT_ID,
                        () ->
                                records.unfinished().stream()
                                        .map(
                                                change ->
                                                        new RecoveryTarget(
                                                                change.getConversationSessionId(),
                                                                change.getChangeId()))
                                        .toList());
        // 不捕获可变 Entity，也不在后台重查 unfinished，否则新 SUBMITTING 会被误判为遗留失败。
        return () ->
                TenantContext.run(
                        TenantContext.TENANT_ID,
                        () ->
                                targets.forEach(
                                        target ->
                                                submissions.reconcile(
                                                        target.sessionId(), target.changeId())));
    }

    /** 仅保留恢复归属定位所需的不可变 ID，不携带可能已经变化的提交状态。 */
    private record RecoveryTarget(String sessionId, String changeId) {}
}
