package com.datagent.agentv2.conversation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 受理摘要包含工作区，旧无 Git 请求保持历史摘要与重放接口。 */
class GitExecutionWorkspaceTest {
    private static final String SESSION = "session";
    private static final String CONTENT = "同一条开发指令";
    private static final String KEY = "request";
    private static final String A = "workspace-a";
    private static final String B = "workspace-b";

    @Test
    void workspaceIsPartOfIdempotencyHash() {
        var transactions = mock(AgentExecutionSubmissionTransactionService.class);
        var service = new AgentExecutionSubmissionService(transactions);
        service.submit(SESSION, CONTENT, KEY, A);
        service.submit(SESSION, CONTENT, KEY, B);
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(transactions, times(2))
                .createOrReplay(eq(SESSION), eq(CONTENT), eq(KEY), hashes.capture(), anyString());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void absentWorkspaceKeepsLegacyPayloadHash() {
        var transactions = mock(AgentExecutionSubmissionTransactionService.class);
        new AgentExecutionSubmissionService(transactions).submit(SESSION, CONTENT, KEY, null);
        verify(transactions)
                .createOrReplay(
                        SESSION,
                        CONTENT,
                        KEY,
                        AgentExecutionSubmissionService.payloadHash(CONTENT));
    }
}
