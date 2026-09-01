package com.datagent.agentv2.controller;

import com.datagent.agentv2.conversation.execution.AgentExecutionControlService;
import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationSummaryView;
import com.datagent.agentv2.conversation.session.ConversationTitleService;
import com.datagent.agentv2.conversation.session.ConversationView;
import com.datagent.agentv2.conversation.stream.ConversationEventStreamFacade;
import com.datagent.agentv2.conversation.summary.ConversationSummaryStreamFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    private static final String SUMMARY_EVENTS_PATH = "/summary-events";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ConversationApplicationService conversations;
    private final ConversationTitleService titles;
    private final AgentExecutionControlService controls;
    private final ConversationEventStreamFacade eventStreams;
    private final ConversationSummaryStreamFacade summaryStream;

    @GetMapping
    List<ConversationSummaryView> list() {
        return conversations.listSummaries();
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

    @PatchMapping("/{conversationSessionId}")
    ConversationView rename(
            @PathVariable String conversationSessionId,
            @Valid @RequestBody RenameConversationSessionRequest request) {
        // 标题服务完成受版本保护的局部写入，再复用完整快照装配返回值。
        titles.rename(conversationSessionId, request.title(), request.expectedVersion());
        return conversations.get(conversationSessionId);
    }

    @GetMapping(value = SUMMARY_EVENTS_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamSummaries() {
        return summaryStream.open();
    }

    @GetMapping(
            value = "/{conversationSessionId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String conversationSessionId) {
        // 每次连接都从权威会话快照开始，客户端无需提交历史续传位置。
        return eventStreams.open(conversationSessionId);
    }

    @PostMapping("/{conversationSessionId}/messages")
    ResponseEntity<ConversationView> submitMessage(
            @PathVariable String conversationSessionId,
            @Valid @RequestBody SubmitConversationMessageRequest request,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return ResponseEntity.accepted()
                .body(
                        conversations.submitMessage(
                                conversationSessionId, request.content(), idempotencyKey));
    }

    @PostMapping("/{conversationSessionId}/executions/{executionId}:cancel")
    ResponseEntity<ConversationView.AgentExecutionView> cancelExecution(
            @PathVariable String conversationSessionId, @PathVariable String executionId) {
        return ResponseEntity.accepted()
                .body(
                        conversations.controlView(
                                conversationSessionId,
                                controls.cancel(conversationSessionId, executionId)));
    }

    /** 创建对话会话所需的可选展示信息。 */
    record CreateConversationSessionRequest(
            @Size(max = 120) String title, @Size(max = 120) String tag) {}

    /** 局部更新会话标题所需的标题内容和会话乐观锁版本。 */
    record RenameConversationSessionRequest(
            @NotBlank @Size(max = 120) String title, @Min(0) long expectedVersion) {}

    /** 向指定对话会话提交用户消息的请求。 */
    record SubmitConversationMessageRequest(@NotBlank @Size(max = 8000) String content) {}
}
