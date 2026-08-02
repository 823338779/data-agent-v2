package com.datagent.agentv2.controller;

import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 暴露附着在会话集合资源上的完整重排操作。 */
@RestController
@RequiredArgsConstructor
public class ConversationOrderingController {

    private static final String REORDER_PATH = "/api/v1/agent-sessions:reorder";

    private final ConversationApplicationService conversations;

    @PostMapping(REORDER_PATH)
    ReorderConversationSessionsResponse reorderSessions(
            @Valid @RequestBody ReorderConversationSessionsRequest request) {
        var reordered =
                conversations.reorder(
                        request.conversationSessionIds(), request.expectedOrderingVersion());
        return new ReorderConversationSessionsResponse(
                reordered.orderingVersion(), conversations.list());
    }

    /** 携带完整可见会话集合和乐观版本的重排请求。 */
    record ReorderConversationSessionsRequest(
            @NotEmpty List<@NotBlank String> conversationSessionIds,
            @Min(0) long expectedOrderingVersion) {}

    /** 返回重排后的版本和完整服务端顺序，供冲突恢复复用。 */
    record ReorderConversationSessionsResponse(
            long orderingVersion, List<ConversationView> sessions) {}
}
