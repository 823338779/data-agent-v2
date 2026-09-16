package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.context.*;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.model.api.*;
import com.datagent.agentv2.conversation.react.*;
import com.datagent.agentv2.conversation.runtime.*;
import com.datagent.agentv2.conversation.tool.*;
import com.datagent.agentv2.conversation.tool.runtime.*;
import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.context.MemoryContextRenderer;
import com.datagent.agentv2.memory.tool.*;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.skill.runtime.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.json.JsonMapper;

class MemoryContextRequestFactoryTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MemoryService memories = mock(MemoryService.class);
    private final WebMemoryEvidenceAdapter evidence = mock(WebMemoryEvidenceAdapter.class);
    private final MemoryInvocationRegistry registry = new MemoryInvocationRegistry();
    private final AgentExecutionService executions = mock(AgentExecutionService.class);
    private final ContextModelBudgetProvider budgets = mock(ContextModelBudgetProvider.class);
    private final ExecutionSkillViews skills = mock(ExecutionSkillViews.class);
    private final ContextModelMessageMapper mappings = new ContextModelMessageMapper(json);
    private final MemoryTool tool =
            new MemoryTool(new MemoryToolDispatcher(memories, json), registry, json);
    private final ToolModelDefinitionMapper definitions = new ToolModelDefinitionMapper(json);
    private final LocalAgentExecution run =
            new LocalAgentExecution("tenant", "session", "execution", 1);
    private final ConversationContextRequestFactory factory =
            new ConversationContextRequestFactory(
                    executions,
                    budgets,
                    mappings,
                    skills,
                    memories,
                    evidence,
                    registry,
                    new MemoryContextRenderer(new MemoryProperties()));

    @BeforeEach
    void setup() {
        var execution = new AgentExecution();
        execution.setConversationSessionId("session");
        when(executions.findByExecutionId(anyString())).thenReturn(execution);
        when(budgets.currentBudget()).thenReturn(new ContextBudget(10000, 100, 100, 0));
        var view = mock(ExecutionSkillView.class);
        when(view.systemPrompt()).thenReturn("");
        when(skills.require(anyString())).thenReturn(view);
        when(memories.snapshot()).thenReturn(snapshot(true));
        when(evidence.currentUserEvidence("execution"))
                .thenReturn(
                        List.of(
                                new MemoryEvidence(
                                        "real-user",
                                        MemoryEvidence.Kind.USER_MESSAGE,
                                        "session",
                                        "execution",
                                        1,
                                        "user",
                                        1,
                                        "请记住使用中文",
                                        LocalDateTime.now())));
    }

    @Test
    void newSessionReadsSamePersonalSnapshotAndUsesPersistedDatabaseEvidence() {
        var messages = mock(ConversationMessageService.class);
        var user =
                ConversationMessage.user(
                        "db-user", "new-session", "中文", 1, OperatorContext.requiredUserId());
        user.associateExecution("new-execution");
        user.setVersion(1L);
        var databaseTime = LocalDateTime.of(2026, 9, 17, 1, 0);
        user.setMemoryEvidenceCreatedAt(databaseTime);
        when(messages.findByExecutionId("new-execution")).thenReturn(List.of(user));
        var adapter =
                new WebMemoryEvidenceAdapter(
                        messages, Mappers.getMapper(WebMemoryEvidenceMapper.class));
        var newFactory =
                new ConversationContextRequestFactory(
                        executions,
                        budgets,
                        mappings,
                        skills,
                        memories,
                        adapter,
                        registry,
                        new MemoryContextRenderer(new MemoryProperties()));
        var execution = new AgentExecution();
        execution.setConversationSessionId("new-session");
        when(executions.findByExecutionId("new-execution")).thenReturn(execution);
        var newRun = new LocalAgentExecution("tenant", "new-session", "new-execution", 1);
        var request =
                newFactory.create(
                        newRun,
                        new ModelInvocationCoordinator.ModelInvocationBoundary(1, 1),
                        definitions.toModelDefinitions(List.of(tool)));
        assertThat(request.runtimeInput().contributions().getFirst().blocks())
                .singleElement()
                .asString()
                .contains("使用中文");
        assertThat(request.runtimeInput().metadata().getFirst().content()).contains("db-user");
        assertThat(
                        registry.evidenceFor(
                                new AgentTool.InvocationContext(
                                        "tenant", "new-execution", 1, "call", "key")))
                .singleElement()
                .satisfies(e -> assertThat(e.createdAt()).isEqualTo(databaseTime));
        assertThat(user.getContent()).isEqualTo("中文");
        verify(messages).findByExecutionId("new-execution");
        verifyNoMoreInteractions(messages);
    }

    @Test
    void snapshotIsSharedWithRegistryAndNextInvocationObservesDeletion() {
        var first = create(1, definitions.toModelDefinitions(List.of(tool)));
        assertThat(first.runtimeInput().contributions())
                .singleElement()
                .satisfies(c -> assertThat(c.blocks()).singleElement().asString().contains("使用中文"));
        assertThat(first.runtimeInput().metadata())
                .singleElement()
                .satisfies(
                        m ->
                                assertThat(m.content())
                                        .contains("real-user")
                                        .doesNotContain("请记住使用中文"));
        assertThat(registry.contextFor(invocation(1)).expectedRevision()).isEqualTo(4);
        verify(memories, times(1)).snapshot();
        when(memories.snapshot()).thenReturn(snapshot(false));
        var next = create(2, definitions.toModelDefinitions(List.of(tool)));
        assertThat(next.runtimeInput().contributions().getFirst().blocks()).isEmpty();
        assertThat(first.runtimeInput().contributions().getFirst().blocks()).isNotEmpty();
    }

    @Test
    void unavailableSnapshotContinuesMainRequestAndRejectsWritesWithoutGuessingRevision() {
        when(memories.snapshot()).thenThrow(new IllegalStateException("private database failure"));
        var request = create(1, definitions.toModelDefinitions(List.of(tool)));
        assertThat(request.runtimeInput().contributions()).isEmpty();
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                "{\"action\":\"add\",\"kind\":\"person_profile\",\"content\":\"中文\",\"write_reason\":\"user_requested\",\"evidence_message_ids\":[\"real-user\"]}"),
                                        invocation(1)))
                .isInstanceOfSatisfying(
                        ToolBusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("UNAVAILABLE"));
        verify(memories, never()).execute(any(), any());
    }

    @Test
    void missingEvidenceIsNotInventedAndExplicitToolDisableIsRespected() {
        when(evidence.currentUserEvidence("execution")).thenReturn(List.of());
        assertThat(
                        create(1, definitions.toModelDefinitions(List.of(tool)))
                                .runtimeInput()
                                .metadata())
                .isEmpty();
        var disabled =
                new ExecutionToolViews(
                        new ToolSupplyRegistry(List.of(tool)),
                        new ToolPolicyProperties(List.of(), List.of()));
        assertThat(disabled.open("disabled").visibleTools()).isEmpty();
        assertThat(create(2, List.of()).toolDefinitions()).isEmpty();
    }

    @Test
    void actualModelCallReceivesFormalMemorySchemaAndRealEvidenceMetadata() {
        var views =
                new ExecutionToolViews(
                        new ToolSupplyRegistry(List.of(tool)),
                        new ToolPolicyProperties(List.of("memory"), List.of("memory")));
        views.open(run.executionId());
        var contexts = mock(ConversationContextStrategyResolver.class);
        when(contexts.prepare(any()))
                .thenAnswer(
                        call -> {
                            ConversationContextRequest request = call.getArgument(0);
                            return new PreparedConversationContext(
                                    "test", request.runtimeInput().metadata(), 0, false, 0);
                        });
        var invocations = mock(ModelInvocationCoordinator.class);
        when(invocations.beginModelInvocation(run))
                .thenReturn(new ModelInvocationCoordinator.ModelInvocationBoundary(1, 1));
        var model = mock(AgentModelGateway.class);
        when(model.complete(anyList(), anyList(), any()))
                .thenAnswer(
                        call -> {
                            List<ModelInputMessage> input = call.getArgument(0);
                            List<ModelToolDefinition> tools = call.getArgument(1);
                            assertThat(input)
                                    .anySatisfy(m -> assertThat(m.content()).contains("real-user"));
                            assertThat(tools)
                                    .singleElement()
                                    .satisfies(
                                            t -> {
                                                assertThat(t.name()).isEqualTo("memory");
                                                assertThat(
                                                                json.valueToTree(t.parameters())
                                                                        .path("oneOf")
                                                                        .size())
                                                        .isEqualTo(4);
                                            });
                            return new ModelInvocationResult.FinalAnswer("done");
                        });
        new ReactExecutionEngine(
                        model,
                        contexts,
                        factory,
                        mappings,
                        invocations,
                        mock(ToolExecutionCoordinator.class),
                        views,
                        definitions,
                        mock(LocalExecutionGuard.class),
                        new ReactLimitsProperties(4, 4),
                        registry)
                .execute(run);
        verify(model).complete(anyList(), anyList(), any());
    }

    private ConversationContextRequest create(long sequence, List<ModelToolDefinition> tools) {
        return factory.create(
                run, new ModelInvocationCoordinator.ModelInvocationBoundary(sequence, 1), tools);
    }

    private AgentTool.InvocationContext invocation(long sequence) {
        return new AgentTool.InvocationContext("tenant", "execution", sequence, "call", "key");
    }

    private MemorySnapshot snapshot(boolean entries) {
        var now = LocalDateTime.now();
        return new MemorySnapshot(
                new MemoryState(4, 0, true, true, true, 1, now),
                entries
                        ? List.of(
                                new MemoryEntry(
                                        "memory-1",
                                        MemoryKind.PERSON_PROFILE,
                                        "使用中文",
                                        "hash",
                                        1,
                                        4,
                                        MemorySource.USER_UI,
                                        true,
                                        1,
                                        now,
                                        now,
                                        now,
                                        null))
                        : List.of(),
                Map.of());
    }
}
