package com.datagent.agentv2.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.MemoryWriteContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MemoryInvocationRegistryTest {

    private final MemoryInvocationRegistry registry = new MemoryInvocationRegistry();

    @Test
    void freezesEvidenceAndRevisionAcrossToolExecutionThread() {
        MemorySnapshot snapshot = snapshot(7);
        MemoryEvidence evidence = evidence("message-1");
        registry.open("execution-1", 3, snapshot, List.of(evidence));

        MemoryWriteContext context =
                CompletableFuture.supplyAsync(() -> registry.contextFor(invocation(3))).join();

        assertThat(context.caller()).isEqualTo(MemoryWriteContext.Caller.MAIN);
        assertThat(context.requestId()).isEqualTo("execution-1:call-1");
        assertThat(context.executionId()).isEqualTo("execution-1");
        assertThat(context.conversationId()).isEqualTo("conversation-1");
        assertThat(context.expectedRevision()).isEqualTo(7);
        assertThat(context.promptVersion()).isEqualTo(MemoryToolPrompts.MAIN_VERSION);
        assertThat(registry.evidenceFor(invocation(3))).containsExactly(evidence);
        assertThat(registry.snapshotFor(invocation(3))).isSameAs(snapshot);
    }

    @Test
    void advancesOnlyFromItsOwnExpectedRevisionAndNeverMovesBackwardOnReplay() {
        registry.open("execution-1", 1, snapshot(7), List.of(evidence("message-1")));

        registry.advance("execution-1", 1, 7, 8);
        assertThat(registry.contextFor(invocation(1)).expectedRevision()).isEqualTo(8);

        registry.advance("execution-1", 1, 8, 7);
        assertThat(registry.contextFor(invocation(1)).expectedRevision()).isEqualTo(8);
        assertThatThrownBy(() -> registry.advance("execution-1", 1, 7, 9))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesExplicitInvocationKeyAndCleansUpByInvocationOrExecution() {
        registry.open("execution-1", 1, snapshot(1), List.of(evidence("message-1")));
        registry.open("execution-1", 2, snapshot(2), List.of(evidence("message-1")));

        assertThat(registry.contextFor(invocation(1)).expectedRevision()).isEqualTo(1);
        assertThat(registry.contextFor(invocation(2)).expectedRevision()).isEqualTo(2);

        registry.close("execution-1", 1);
        assertThatThrownBy(() -> registry.contextFor(invocation(1)))
                .isInstanceOf(IllegalStateException.class);
        registry.closeExecution("execution-1");
        assertThatThrownBy(() -> registry.contextFor(invocation(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AgentTool.InvocationContext invocation(long sequence) {
        return new AgentTool.InvocationContext(
                "tenant_local", "execution-1", sequence, "call-1", "execution-1:call-1");
    }

    private static MemorySnapshot snapshot(long revision) {
        return new MemorySnapshot(
                new MemoryState(revision, 0, true, true, true, 1, LocalDateTime.now()),
                List.of(),
                Map.of(
                        MemoryKind.PERSON_PROFILE,
                        new MemoryQuota(0, 800),
                        MemoryKind.COLLABORATION_PREFERENCE,
                        new MemoryQuota(0, 1500)));
    }

    private static MemoryEvidence evidence(String id) {
        return new MemoryEvidence(
                id,
                MemoryEvidence.Kind.USER_MESSAGE,
                "conversation-1",
                "execution-1",
                1,
                "user_admin",
                1,
                "请记住我喜欢简洁回答",
                LocalDateTime.now());
    }
}
