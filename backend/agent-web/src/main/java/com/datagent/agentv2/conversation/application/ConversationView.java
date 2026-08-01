package com.datagent.agentv2.conversation.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ConversationView(
        String id,
        String title,
        String tag,
        String sessionType,
        Instant createdAt,
        Instant updatedAt,
        long version,
        long metricIdentityBindingVersion,
        Object metricIdentity,
        List<MessageView> messages,
        RunView run) {

    public record MessageView(
            String id,
            String role,
            String author,
            String content,
            Instant createdAt,
            List<Object> citations) {}

    public record RunView(
            String id,
            String status,
            int progress,
            String objective,
            Instant cancellationRequestedAt,
            List<RunEventView> events,
            Object pendingConfirmation,
            Object pendingInteraction,
            RunStreamView stream) {}

    public record RunEventView(
            String id,
            long sequence,
            String type,
            Instant occurredAt,
            String runId,
            String messageId,
            String title,
            String summary,
            String toolName,
            Map<String, Object> input,
            Map<String, Object> output,
            String confirmationId,
            Boolean approved,
            String status) {}

    public record RunStreamView(
            long executionAttempt,
            long modelTurnSequence,
            String phase,
            long sequence,
            String thinking,
            String answer) {}
}
