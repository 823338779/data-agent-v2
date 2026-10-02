package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.*;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.memory.review.MemoryReviewMessage;
import com.datagent.agentv2.persistence.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebMemoryReviewHistoryAdapterTest {
    @Test
    void classifiesWholeRowsAndUsesDatabaseEvidenceTime() {
        var messages = mock(ConversationMessageService.class);
        var executions = mock(AgentExecutionService.class);
        var service = mock(MemoryService.class);
        var cutoff = LocalDateTime.of(2026, 9, 17, 10, 0);
        var state = new MemoryState(0, 0, true, true, true, 1, cutoff);
        when(service.snapshot()).thenReturn(new MemorySnapshot(state, List.of(), Map.of()));
        var user = ConversationMessage.user("u", "s", "正文", 1, "user_admin");
        user.associateExecution("done");
        user.acknowledgePersistence(1L, cutoff.minusDays(1), cutoff);
        var assistant = spy(ConversationMessage.assistant("a", "s", "done", "最终正文", 2));
        doThrow(new AssertionError("审查不得读取工具/思考过程")).when(assistant).getThinkingProcess();
        assistant.acknowledgePersistence(1L, cutoff, cutoff);
        var legacy = ConversationMessage.user("legacy", "s", "旧正文", 3, "user_admin");
        legacy.associateExecution("running");
        legacy.acknowledgePersistence(0L, cutoff.plusDays(1), null);
        var pending = ConversationMessage.assistant("p", "s", "running", "部分正文", 5);
        pending.acknowledgePersistence(0L, cutoff, cutoff);
        when(messages.findReviewPage("s", 0, 6, 10))
                .thenReturn(List.of(user, assistant, legacy, pending));
        var done =
                AgentExecution.builder()
                        .executionId("done")
                        .conversationSessionId("s")
                        .triggeringConversationMessageId("u")
                        .requestIdempotencyKey("key")
                        .requestPayloadHash("hash")
                        .status(ExecutionStatus.DONE)
                        .hardDeadlineAt(Instant.now().plusSeconds(60))
                        .build();
        var running =
                AgentExecution.running(
                        "running", "s", "legacy", "key2", "hash", Instant.now().plusSeconds(60));
        when(executions.findByExecutionIds(anyCollection())).thenReturn(List.of(done, running));
        var claim =
                new MemoryReviewClaim(
                        "j", "s", 1, 1, 1, "o", 1, 0, 6, Instant.now().plusSeconds(120));
        var rows =
                new WebMemoryReviewHistoryAdapter(
                                messages, executions, service, new WebMemoryEvidenceMapperImpl())
                        .read(claim, 0, 10);
        assertThat(rows)
                .extracting(MemoryReviewMessage::state)
                .containsExactly(
                        MemoryReviewMessage.State.ELIGIBLE, MemoryReviewMessage.State.ELIGIBLE,
                        MemoryReviewMessage.State.EXCLUDED, MemoryReviewMessage.State.EXCLUDED,
                        MemoryReviewMessage.State.PENDING, MemoryReviewMessage.State.EXCLUDED);
        assertThat(rows.getFirst().evidence().createdAt()).isEqualTo(cutoff);
        assertThat(rows.get(1).evidence().kind()).isEqualTo(MemoryEvidence.Kind.ASSISTANT_CONTEXT);
        verify(executions, times(1)).findByExecutionIds(anyCollection());
        verify(assistant, never()).getThinkingProcess();
    }
}
