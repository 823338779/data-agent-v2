package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import com.datagent.agentv2.conversation.application.RunControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent-sessions")
@RequiredArgsConstructor
public class ConversationController {

    private static final String CANCEL_RUN_PATH = "/{sessionId}/runs/{runId}:cancel";
    private static final String RETRY_RUN_PATH = "/{sessionId}/runs/{runId}:retry";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ConversationApplicationService conversations;
    private final RunControlService controls;
    private final ConversationEventStreamFacade eventStreams;

    @GetMapping
    List<ConversationView> list() {
        return conversations.list();
    }

    @PostMapping
    ResponseEntity<ConversationView> create(@Valid @RequestBody CreateSessionRequest request) {
        ConversationView created = conversations.create(request.title(), request.tag());
        return ResponseEntity.created(URI.create("/api/v1/agent-sessions/" + created.id()))
                .body(created);
    }

    @GetMapping("/{sessionId}")
    ConversationView get(@PathVariable String sessionId) {
        return conversations.get(sessionId);
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String sessionId) {
        return eventStreams.open(sessionId);
    }

    @PostMapping("/{sessionId}/messages")
    ResponseEntity<ConversationView> submitMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody SubmitMessageRequest request,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", defaultValue = "request_unknown")
                    String requestId) {
        return ResponseEntity.accepted()
                .body(
                        conversations.submitMessage(
                                sessionId, request.content(), idempotencyKey, requestId));
    }

    @PostMapping(CANCEL_RUN_PATH)
    ResponseEntity<ConversationView.RunView> cancelRun(
            @PathVariable String sessionId, @PathVariable String runId) {
        return ResponseEntity.accepted()
                .body(conversations.controlView(sessionId, controls.cancel(sessionId, runId)));
    }

    @PostMapping(RETRY_RUN_PATH)
    ResponseEntity<ConversationView.RunView> retryRun(
            @PathVariable String sessionId,
            @PathVariable String runId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return ResponseEntity.accepted()
                .body(
                        conversations.controlView(
                                sessionId, controls.retry(sessionId, runId, idempotencyKey)));
    }

    record CreateSessionRequest(@Size(max = 120) String title, @Size(max = 120) String tag) {}

    record SubmitMessageRequest(@NotBlank @Size(max = 8000) String content) {}
}
