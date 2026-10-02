package com.datagent.agentv2.conversation.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DomainStateTransitionsTest {
    @Test
    void completedUserMessageCannotBeOverwrittenAsAssistantOutput() {
        var message = ConversationMessage.user("m", "s", "user input", 1, "u");
        assertThrows(
                IllegalStateException.class,
                () -> message.completeAssistantContent("model output"));
        assertEquals("user input", message.getContent());
    }

    @Test
    void messageCannotMoveToAnotherExecution() {
        var message = ConversationMessage.assistantPlaceholder("m", "s", "first", 1);
        assertThrows(IllegalStateException.class, () -> message.associateExecution("second"));
        assertEquals("first", message.getExecutionId());
    }

    @Test
    void cancellationCannotReopenATerminalExecution() {
        var execution = AgentExecution.running("e", "s", "m", "key", "hash", Instant.now());
        execution.cancel();
        assertEquals(ExecutionStatus.CANCELLED, execution.getStatus());
        assertThrows(IllegalStateException.class, execution::cancel);
    }

    @Test
    void restoredCheckpointRejectsNegativeWatermark() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentExecutionStreamCheckpoint.builder()
                                .executionId("e")
                                .phase(
                                        com.datagent.agentv2.conversation.enums.ExecutionPhase
                                                .ANSWERING)
                                .outputDeltaSequence(-1L)
                                .build());
    }

    @Test
    void persistenceAcknowledgementCannotRewindVersion() {
        var execution = AgentExecution.running("e", "s", "m", "key", "hash", Instant.now());
        execution.acknowledgePersistence(2);
        assertThrows(IllegalArgumentException.class, () -> execution.acknowledgePersistence(1));
        assertEquals(2L, execution.getVersion());
    }
}
