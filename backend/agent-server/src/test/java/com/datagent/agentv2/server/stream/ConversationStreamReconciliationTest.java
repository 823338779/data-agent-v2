package com.datagent.agentv2.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationView;
import com.datagent.agentv2.conversation.stream.ConversationEventStreamFacade;
import com.datagent.agentv2.conversation.stream.LocalExecutionDelta;
import com.datagent.agentv2.conversation.stream.LocalSseRegistry;
import com.datagent.agentv2.conversation.summary.ConversationSummaryStreamFacade;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConversationStreamReconciliationTest {
    @Test
    void schedulerRepairsADeltaGapAndSendsBufferedDeltaAfterSnapshot() throws Exception {
        var conversations = mock(ConversationApplicationService.class);
        var registry = new LocalSseRegistry();
        var facade = new ConversationEventStreamFacade(conversations, registry);
        var checkpoint =
                new ConversationView.AgentExecutionStreamCheckpointView(
                        1, ExecutionPhase.ANSWERING, 1, "a");
        var execution =
                new ConversationView.AgentExecutionView(
                        "run", ExecutionStatus.RUNNING, 70, "question", List.of(), checkpoint);
        var view =
                new ConversationView(
                        "session", "title", "", null, null, null, null, 0, List.of(), execution);
        when(conversations.streamBootstrap("session"))
                .thenReturn(new ConversationApplicationService.SessionStreamBootstrapView(view, 1));
        when(conversations.reconcileStreams(anyMap()))
                .thenReturn(
                        new ConversationApplicationService.SessionStreamRecoveryView(
                                Map.of(),
                                Map.of(
                                        "session",
                                        new ConversationApplicationService
                                                .ExecutionStreamCheckpointView(
                                                "run", 1, ExecutionPhase.ANSWERING, 2, "ab")),
                                Map.of(),
                                Map.of("session", true)));
        var mvc = MockMvcBuilders.standaloneSetup(new StreamController(facade)).build();
        var response = mvc.perform(get("/stream")).andReturn();
        try (var runtime = new AnnotationConfigApplicationContext()) {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () ->
                                    assertThat(response.getResponse().getContentAsString())
                                            .contains("session_snapshot"));
            registry.publish(
                    new LocalExecutionDelta(
                            TenantContext.requiredTenantId(),
                            "session",
                            "run",
                            1,
                            3,
                            "c",
                            Instant.now()));
            assertThat(response.getResponse().getContentAsString())
                    .doesNotContain("event:assistant_delta");
            runtime.registerBean(ConversationEventStreamFacade.class, () -> facade);
            runtime.registerBean(
                    ConversationSummaryStreamFacade.class,
                    () -> mock(ConversationSummaryStreamFacade.class));
            runtime.register(ConversationStreamRuntimeConfiguration.class);
            runtime.refresh();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(4))
                    .untilAsserted(
                            () -> {
                                String events = response.getResponse().getContentAsString();
                                assertThat(events)
                                        .contains(
                                                "event:stream_snapshot",
                                                "event:assistant_delta",
                                                "\"answer\":\"ab\"",
                                                "\"delta\":\"c\"");
                                assertThat(events.indexOf("event:stream_snapshot"))
                                        .isLessThan(events.indexOf("event:assistant_delta"));
                            });
        } finally {
            response.getRequest().getAsyncContext().complete();
        }
    }

    @RestController
    private record StreamController(ConversationEventStreamFacade facade) {
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter open() {
            return facade.open("session");
        }
    }
}
