package com.datagent.agentv2.conversation.api;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-sessions")
public class ConversationController {

    private final ConversationApplicationService conversations;

    public ConversationController(ConversationApplicationService conversations) {
        this.conversations = conversations;
    }

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

    @PostMapping("/{sessionId}/messages")
    ResponseEntity<ConversationView> submitMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody SubmitMessageRequest request,
            @RequestHeader(value = "X-Request-Id", defaultValue = "request_unknown")
                    String requestId) {
        return ResponseEntity.accepted()
                .body(conversations.submitMessage(sessionId, request.content(), requestId));
    }

    record CreateSessionRequest(@Size(max = 120) String title, @Size(max = 120) String tag) {}

    record SubmitMessageRequest(@NotBlank @Size(max = 8000) String content) {}
}
