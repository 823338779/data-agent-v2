package com.datagent.agentv2.conversation.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class ThinkingStreamSanitizationTest {
    @Test
    void longQuotedSecretDoesNotExhaustTheRegexStack() {
        var sanitizer = new ProcessPayloadSanitizer(JsonMapper.builder().build());
        for (String quote : java.util.List.of("\"", "'")) {
            String prefix = "password=" + quote + "x".repeat(20_000);
            assertThat(sanitizer.sanitizeText(prefix)).isEqualTo("password=[REDACTED]");
            assertThat(sanitizer.sanitizeText(prefix + quote + "; safe=visible"))
                    .isEqualTo("password=[REDACTED]; safe=visible");
        }
    }

    @Test
    void everySplitKeepsSecretsOutOfPublishedAndPersistedThinking() {
        for (String original :
                java.util.List.of(
                        "password=audit_dummy_secret; safe=visible",
                        "password=\"audit_dummy_secret,continued\"; safe=visible")) {
            for (int split = 1; split < original.length(); split++) {
                var json = JsonMapper.builder().build();
                var executions = mock(AgentExecutionService.class);
                var messages = mock(ConversationMessageService.class);
                var published = new ArrayList<String>();
                ApplicationEventPublisher events =
                        event -> {
                            if (event instanceof ProcessAuditEvent audit) {
                                published.add(
                                        json.writeValueAsString(audit.safeFullDocument(json)));
                            }
                        };
                var assistant =
                        ConversationMessage.assistantPlaceholder("message", "session", "run", 2);
                when(executions.lockById("run"))
                        .thenReturn(
                                AgentExecution.running(
                                        "run",
                                        "session",
                                        "user",
                                        "key",
                                        "hash",
                                        Instant.now().plusSeconds(60)));
                when(messages.lockAssistantByExecutionId("run")).thenReturn(assistant);
                when(messages.updateById(any(ConversationMessage.class))).thenReturn(true);
                var coordinator =
                        new ConversationProcessCoordinator(
                                executions,
                                messages,
                                new ProcessPayloadSanitizer(json),
                                new ProcessPayloadLimiter(json),
                                new ConversationProcessProperties(16384, 32768, 65536),
                                events,
                                java.util.List.of());
                var run = new LocalAgentExecution("tenant", "session", "run", 2);
                var item =
                        coordinator.startThinking(
                                run,
                                1,
                                "provider",
                                "model",
                                original.substring(0, split),
                                Instant.now());
                coordinator.appendThinking(run, item, original.substring(split), Instant.now());
                coordinator.finishThinking(
                        run, item, ThinkingProcessStatus.SUCCEEDED, Instant.now());
                assertThat(published)
                        .allSatisfy(
                                document ->
                                        assertThat(document)
                                                .doesNotContain(
                                                        "audit_dummy", "audit_", "dummy_secret"));
                assertThat(json.writeValueAsString(assistant.getThinkingProcess()))
                        .contains("password=[REDACTED]", "safe=visible")
                        .doesNotContain("audit_dummy_secret");
            }
        }
    }
}
