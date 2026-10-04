package com.datagent.agentv2.conversation.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference;
import com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository;
import org.junit.jupiter.api.Test;

class HierarchicalContextPayloadArchiveTest {
    private static final String ID = "source";
    private static final String TEXT = "安全内容";
    private final ContextPayloadRepository payloads = mock(ContextPayloadRepository.class);
    private final ContextPayloadArchive archive = new ContextPayloadArchive(payloads, 1048576);

    @Test
    void processArchivesSanitizedInputAndOutputBeforePublishingReferences() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var executions = mock(com.datagent.agentv2.persistence.service.AgentExecutionService.class);
        var messages =
                mock(com.datagent.agentv2.persistence.service.ConversationMessageService.class);
        var assistant =
                com.datagent.agentv2.conversation.domain.ConversationMessage.assistantPlaceholder(
                        "message", ID, ID, 2);
        when(executions.lockById(ID))
                .thenReturn(
                        com.datagent.agentv2.conversation.domain.AgentExecution.running(
                                ID,
                                ID,
                                "user",
                                "key",
                                "hash",
                                java.time.Instant.now().plusSeconds(60)));
        when(messages.lockAssistantByExecutionId(ID)).thenReturn(assistant);
        when(messages.updateById(
                        any(com.datagent.agentv2.conversation.domain.ConversationMessage.class)))
                .thenReturn(true);
        var published = new java.util.ArrayList<String>();
        var coordinator =
                new ConversationProcessCoordinator(
                        executions,
                        messages,
                        new ProcessPayloadSanitizer(json),
                        new ProcessPayloadLimiter(json),
                        new ConversationProcessProperties(16384, 32768, 65536),
                        event -> published.add(json.writeValueAsString(event)),
                        java.util.List.of(archive));
        var run =
                new com.datagent.agentv2.conversation.runtime.LocalAgentExecution(
                        "tenant", ID, ID, 2);
        var content = json.createObjectNode().put("password", "fixture-secret").put("safe", TEXT);
        var item =
                coordinator.prepareTool(
                        run,
                        1,
                        new com.datagent.agentv2.conversation.react.ModelToolCall(ID, ID, content),
                        java.time.Instant.now());
        coordinator.startTool(run, item, java.time.Instant.now());
        coordinator.finishTool(
                run,
                item,
                com.datagent.agentv2.conversation.tool.ToolExecutionResult.succeeded(content),
                java.time.Instant.now());
        var saved =
                org.mockito.ArgumentCaptor.forClass(
                        com.datagent.agentv2.context.hierarchical.persistence.entity
                                .ContextPayloadEntity.class);
        org.mockito.Mockito.verify(payloads, org.mockito.Mockito.times(2))
                .insertOrSelect(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(
                        value ->
                                assertThat(value.getContent())
                                        .contains(TEXT, "[REDACTED]")
                                        .doesNotContain("fixture-secret"));
        assertThat(json.writeValueAsString(assistant.getThinkingProcess()))
                .contains("AVAILABLE")
                .doesNotContain("fixture-secret");
        assertThat(published)
                .allSatisfy(value -> assertThat(value).doesNotContain("fixture-secret"));
    }

    @Test
    void retainsTopLevelIdentityWithoutPromotingNestedBody() {
        String content =
                "{\"validationId\":\"v42\",\"status\":\"RUNNING\",\"body\":{\"status\":\"SUCCEEDED\"}}";
        var result = archive.archive(ID, ID, ID, ContextPayloadReference.Direction.OUTPUT, content);
        assertThat(result.observedFacts())
                .containsEntry("validationId", "v42")
                .containsEntry("status", "RUNNING");
        assertThat(result.observedFacts()).doesNotContainKey("body");
    }

    @Test
    void oversizePayloadHasNoFalseFullReference() {
        var result =
                archive.archive(
                        ID, ID, ID, ContextPayloadReference.Direction.OUTPUT, ID.repeat(262145));
        assertThat(result.reference()).isNull();
        assertThat(result.availability()).isEqualTo(ContextPayloadReference.Availability.OVERSIZE);
        verifyNoInteractions(payloads);
    }

    @Test
    void sameSourceAndHashIsIdempotent() {
        var first = archive.archive(ID, ID, ID, ContextPayloadReference.Direction.INPUT, TEXT);
        assertThat(archive.archive(ID, ID, ID, ContextPayloadReference.Direction.INPUT, TEXT))
                .isEqualTo(first);
        assertThat(first.availability()).isEqualTo(ContextPayloadReference.Availability.AVAILABLE);
        verify(payloads, times(2)).insertOrSelect(any());
    }

    @Test
    void archiveFailureNeverIssuesReferenceOrRetries() {
        doThrow(new IllegalStateException(ID)).when(payloads).insertOrSelect(any());
        assertThatThrownBy(
                        () ->
                                archive.archive(
                                        ID, ID, ID, ContextPayloadReference.Direction.OUTPUT, TEXT))
                .isInstanceOf(IllegalStateException.class);
        verify(payloads).insertOrSelect(any());
    }
}
