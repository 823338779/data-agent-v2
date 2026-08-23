package com.datagent.agentv2.conversation.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public final class LocalExecutionDeltaPublisher {

    private final LocalSseRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ExecutionStreamCheckpointCoordinator.AnswerDelta answer) {
        registry.publish(
                new LocalExecutionDelta(
                        answer.tenantId(),
                        answer.conversationSessionId(),
                        answer.executionId(),
                        answer.modelInvocationSequence(),
                        answer.outputDeltaSequence(),
                        answer.answerDelta(),
                        answer.occurredAt()));
    }
}
