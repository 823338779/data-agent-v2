package com.datagent.agentv2.conversation.tool;

import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public interface AgentTool {

    String name();

    String description();

    JsonNode parametersSchema();

    default Duration timeout() {
        return Duration.ofSeconds(30);
    }

    default boolean supportsIdempotency() {
        return false;
    }

    JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception;

    record InvocationContext(
            String tenantId,
            String runId,
            long modelTurnSequence,
            String toolCallId,
            String idempotencyKey) {}

    final class BusinessFailure extends Exception {

        private final String code;
        private final String safeMessage;

        public BusinessFailure(String code, String safeMessage) {
            super(safeMessage);
            this.code = Objects.requireNonNull(code, "code");
            this.safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
        }

        public String code() {
            return code;
        }

        public String safeMessage() {
            return safeMessage;
        }
    }
}
