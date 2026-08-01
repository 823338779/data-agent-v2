package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ConversationEventStreamFacade {

    private final ConversationApplicationService conversations;

    public SseEmitter open(String sessionId) {
        List<ConversationView.RunEventView> persistedEvents = conversations.replayEvents(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            for (ConversationView.RunEventView event : persistedEvents) {
                emitter.send(
                        SseEmitter.event()
                                .id(event.id())
                                .name(event.type())
                                .data(event, MediaType.APPLICATION_JSON));
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }
}
