package com.datagent.agentv2.conversation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 会话目标不再作为模型消息参数；同一内容仍保持原幂等摘要。 */
class GitExecutionWorkspaceTest {
    @Test
    void messagesUseOnlyTrustedSessionAndStableContentHash() {
        var transactions = mock(AgentExecutionSubmissionTransactionService.class);
        var service = new AgentExecutionSubmissionService(transactions);
        service.submit("session", "开发指标", "key");
        service.submit("session", "开发指标", "key");
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(transactions, times(2))
                .createOrReplay(eq("session"), eq("开发指标"), eq("key"), hashes.capture());
        assertThat(hashes.getAllValues())
                .containsOnly(AgentExecutionSubmissionService.payloadHash("开发指标"));
    }

    @Test
    void distinctContentCannotReusePayloadIdentity() {
        assertThat(AgentExecutionSubmissionService.payloadHash("first"))
                .isNotEqualTo(AgentExecutionSubmissionService.payloadHash("second"));
    }
}
