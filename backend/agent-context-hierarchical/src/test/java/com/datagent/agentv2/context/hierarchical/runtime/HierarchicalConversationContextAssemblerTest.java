package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalConversationContextAssemblerTest {
    private final HierarchicalConversationHistoryLoader history =
            mock(HierarchicalConversationHistoryLoader.class);
    private final HierarchicalCheckpointRepository checkpoints =
            mock(HierarchicalCheckpointRepository.class);
    private final HierarchicalColdRepository colds = mock(HierarchicalColdRepository.class);
    private final HierarchicalConversationContextAssembler assembler =
            new HierarchicalConversationContextAssembler(
                    history,
                    new ConversationTokenEstimator(),
                    checkpoints,
                    colds,
                    new EventContextSummaryValidator(),
                    new EventContextSummaryRenderer());
    private final ConversationContextRequest request = request();
    private final HierarchicalContextPolicySnapshot policy =
            new HierarchicalContextPolicySnapshot(1, .75, .70);

    @BeforeEach
    void defineCheckpoint() {
        when(checkpoints.findCheckpoint("session")).thenReturn(checkpoint(null));
    }

    @Test
    void keepsEveryUncoveredTurnInOrderWithoutSelectingACurrentTurn() {
        when(history.load(request.execution())).thenReturn(turns("old", "failed", "latest"));

        HierarchicalContextState state = assembler.loadAndEstimate(request, policy);
        PreparedConversationContext prepared = assembler.prepareCurrentView(state);

        assertThat(state.loadedThroughMessageSequence()).isEqualTo(6L);
        assertThat(prepared.messages())
                .filteredOn(message -> message.role() == ContextMessage.Role.USER)
                .extracting(ContextMessage::content)
                .containsExactly("old", "failed", "latest");
    }

    @Test
    void rendersColdBeforeEveryUncoveredTurn() {
        when(checkpoints.findCheckpoint("session")).thenReturn(checkpoint("cold-1"));
        when(colds.find("session", "cold-1")).thenReturn(cold());
        when(history.load(request.execution())).thenReturn(turns("old", "failed", "latest"));

        PreparedConversationContext prepared =
                assembler.prepareCurrentView(assembler.loadAndEstimate(request, policy));

        assertThat(prepared.messages().getFirst().role()).isEqualTo(ContextMessage.Role.SYSTEM);
        assertThat(prepared.messages().getFirst().content()).contains("旧事实");
        assertThat(prepared.messages())
                .filteredOn(message -> message.role() == ContextMessage.Role.USER)
                .extracting(ContextMessage::content)
                .containsExactly("failed", "latest");
    }

    private static List<ConversationTurn> turns(String... userContents) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int index = 0; index < userContents.length; index++) {
            long ordinal = index + 1L;
            long start = ordinal * 2L - 1L;
            turns.add(
                    new ConversationTurn(
                            ordinal,
                            start,
                            start + 1L,
                            List.of(
                                    ContextMessage.user(userContents[index]),
                                    ContextMessage.assistant("answer-" + ordinal, null))));
        }
        return List.copyOf(turns);
    }

    private static ConversationContextRequest request() {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", 1L),
                new ContextBudget(65_536, 4_096, 4_096, 0),
                List.of(),
                List.of());
    }

    private static HierarchicalCheckpoint checkpoint(String activeColdId) {
        return new HierarchicalCheckpoint("checkpoint", "session", activeColdId, 0, 0, 0);
    }

    private static HierarchicalCold cold() {
        return new HierarchicalCold(
                "cold-1",
                "session",
                new EventSummaryCoverage(1, 1, 1, 2),
                """
                {
                  "schemaVersion": 2,
                  "coverage": {"startTurnSequence":1,"endTurnSequence":1,"startMessageSequence":1,"endMessageSequence":2},
                  "events": [{
                    "title": {"text":"旧事实","sourceRanges":[{"startMessageSequence":1,"endMessageSequence":1}]},
                    "status": {"value":"ACTIVE","sourceRanges":[{"startMessageSequence":2,"endMessageSequence":2}]},
                    "confirmedFacts": [{"statement":"旧事实","sourceRanges":[{"startMessageSequence":1,"endMessageSequence":2}]}],
                    "userPreferences": [], "workingHypotheses": [], "decisions": [],
                    "rejectedApproaches": [], "toolFindings": [], "openQuestions": [], "nextSteps": []
                  }]
                }
                """,
                100,
                80,
                100,
                "provider",
                "model",
                "0".repeat(64));
    }
}
