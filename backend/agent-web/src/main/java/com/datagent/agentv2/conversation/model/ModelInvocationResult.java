package com.datagent.agentv2.conversation.model;

import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.List;
import java.util.Objects;

public sealed interface ModelInvocationResult
        permits ModelInvocationResult.ToolCalls, ModelInvocationResult.FinalAnswer {

    record ToolCalls(List<ModelToolCall> calls) implements ModelInvocationResult {
        public ToolCalls {
            calls = List.copyOf(calls);
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("calls must not be empty");
            }
        }
    }

    record FinalAnswer(String answer) implements ModelInvocationResult {
        public FinalAnswer {
            Objects.requireNonNull(answer, "answer");
            if (answer.isBlank()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
        }
    }
}
