package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.AgentExecutionControlService;
import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
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

    private static final String CANCEL_EXECUTION_PATH =
            "/{conversationSessionId}/executions/{executionId}:cancel";
    private static final String RETRY_EXECUTION_PATH =
            "/{conversationSessionId}/executions/{executionId}:retry";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ConversationApplicationService conversations;
    private final AgentExecutionControlService controls;
    private final ConversationEventStreamFacade eventStreams;

    @GetMapping
    List<ConversationView> list() {
        return conversations.list();
    }

    @PostMapping
    ResponseEntity<ConversationView> create(
            @Valid @RequestBody CreateConversationSessionRequest request) {
        ConversationView created = conversations.create(request.title(), request.tag());
        return ResponseEntity.created(
                        URI.create("/api/v1/agent-sessions/" + created.conversationSessionId()))
                .body(created);
    }

    @GetMapping("/{conversationSessionId}")
    ConversationView get(@PathVariable String conversationSessionId) {
        return conversations.get(conversationSessionId);
    }

    @GetMapping(
            value = "/{conversationSessionId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable String conversationSessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastExecutionEventId) {
        return eventStreams.open(conversationSessionId, lastExecutionEventId);
    }

    @PostMapping("/{conversationSessionId}/messages")
    ResponseEntity<ConversationView> submitMessage(
            @PathVariable String conversationSessionId,
            @Valid @RequestBody SubmitConversationMessageRequest request,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", defaultValue = "request_unknown")
                    String requestId) {
        return ResponseEntity.accepted()
                .body(
                        conversations.submitMessage(
                                conversationSessionId,
                                request.content(),
                                idempotencyKey,
                                requestId));
    }

    @PostMapping(CANCEL_EXECUTION_PATH)
    ResponseEntity<ConversationView.AgentExecutionView> cancelExecution(
            @PathVariable String conversationSessionId, @PathVariable String executionId) {
        return ResponseEntity.accepted()
                .body(
                        conversations.controlView(
                                conversationSessionId,
                                controls.cancel(conversationSessionId, executionId)));
    }

    @PostMapping(RETRY_EXECUTION_PATH)
    ResponseEntity<ConversationView.AgentExecutionView> retryExecution(
            @PathVariable String conversationSessionId,
            @PathVariable String executionId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return ResponseEntity.accepted()
                .body(
                        conversations.controlView(
                                conversationSessionId,
                                controls.retry(
                                        conversationSessionId, executionId, idempotencyKey)));
    }

    /** 创建对话会话所需的可选展示信息。 */
    record CreateConversationSessionRequest(
            @Size(max = 120) String title, @Size(max = 120) String tag) {}

    /** 向指定对话会话提交用户消息的请求。 */
    record SubmitConversationMessageRequest(@NotBlank @Size(max = 8000) String content) {}
}
