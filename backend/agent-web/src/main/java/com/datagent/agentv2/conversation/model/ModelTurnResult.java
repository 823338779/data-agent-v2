package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.react.ToolCallDocument;
import java.util.List;
import java.util.Objects;

public sealed interface ModelTurnResult
        permits ModelTurnResult.ToolCalls, ModelTurnResult.FinalAnswer {

    record ToolCalls(List<ToolCallDocument> calls) implements ModelTurnResult {
        public ToolCalls {
            calls = List.copyOf(calls);
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("calls must not be empty");
            }
        }
    }

    record FinalAnswer(String answer) implements ModelTurnResult {
        public FinalAnswer {
            Objects.requireNonNull(answer, "answer");
            if (answer.isBlank()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
        }
    }
}
