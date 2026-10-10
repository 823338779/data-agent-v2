package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HierarchicalStepProjectionTest {
    private static final String ID = "call";
    private static final String TOOL = "read";
    private static final String HASH = "0".repeat(64);
    private static final String KEY = "body";
    private static final String LONG = "中文正文".repeat(2000);

    @Test
    void largeArgumentsAndResultsPreserveToolPairs() {
        var source =
                new ContextStepGroup(
                        ContextStepCursor.ORIGIN,
                        new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1),
                        List.of(
                                ContextMessage.assistantToolCalls(
                                        List.of(new ContextToolCall(ID, TOOL, Map.of(KEY, LONG)))),
                                ContextMessage.tool(LONG, ID, TOOL)),
                        HASH,
                        true);
        var projected = new HierarchicalStepProjection().project(source, 512);
        assertThat(projected.messages()).hasSize(2);
        assertThat(projected.messages().getFirst().toolCalls().getFirst().id()).isEqualTo(ID);
        assertThat(projected.messages().getLast().toolCallId()).isEqualTo(ID);
        assertThat(
                        projected
                                .messages()
                                .getLast()
                                .content()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                .length)
                .isLessThanOrEqualTo(512);
        assertThat(projected.messages().getLast().content()).contains("MISSING");
        assertThat(source.messages().getLast().content()).isEqualTo(LONG);
    }

    @Test
    void projectionKeepsAuthoritativeIds() {
        String body =
                "{\"validationId\":\"v1\",\"status\":\"SUCCEEDED\",\"body\":\"" + LONG + "\"}";
        var source =
                new ContextStepGroup(
                        ContextStepCursor.ORIGIN,
                        new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1),
                        List.of(ContextMessage.tool(body, ID, TOOL)),
                        HASH,
                        true);
        var projected = new HierarchicalStepProjection().project(source, 512);
        assertThat(projected.messages().getFirst().content()).contains("v1", "SUCCEEDED");
    }

    @Test
    void projectionKeepsSeparateBuildAndDeploymentFacts() {
        String body =
                "{\"operationId\":\"operation-1\",\"buildId\":\"build-1\",\"buildStatus\":\"SUCCEEDED\",\"deploymentStatus\":\"FAILED\",\"body\":\""
                        + LONG
                        + "\"}";
        var source =
                new ContextStepGroup(
                        ContextStepCursor.ORIGIN,
                        new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1),
                        List.of(ContextMessage.tool(body, ID, TOOL)),
                        HASH,
                        true);
        var content =
                new HierarchicalStepProjection()
                        .project(source, 512)
                        .messages()
                        .getFirst()
                        .content();
        assertThat(content)
                .contains(
                        "operation-1",
                        "build-1",
                        "buildStatus",
                        "SUCCEEDED",
                        "deploymentStatus",
                        "FAILED");
    }

    @Test
    void pollFoldKeepsTransitionsErrorsAndBranchIdentity() {
        var projector = new HierarchicalStepProjection();
        var polls =
                List.of(
                        poll(1, "a", "RUNNING"),
                        poll(2, "a", "RUNNING"),
                        poll(3, "a", "RUNNING"),
                        poll(4, "b", "RUNNING"),
                        poll(5, "b", "FAILED"),
                        poll(6, "b", "SUCCEEDED"));
        var sources = projector.summarySources(polls);
        assertThat(sources).hasSize(4);
        assertThat(sources.getFirst().sources()).hasSize(3);
        assertThat(sources.getFirst().messages()).hasSize(4);
        assertThat(sources.getLast().messages().getLast().content()).contains("SUCCEEDED");
    }

    private ContextStepGroup poll(int index, String branch, String status) {
        String call = ID + index;
        String name = "dbt_query_get";
        var end = new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, index);
        var start =
                index == 1
                        ? ContextStepCursor.ORIGIN
                        : new ContextStepCursor(
                                2, ContextStepCursor.PositionKind.INVOCATION_END, index - 1);
        return new ContextStepGroup(
                start,
                end,
                List.of(
                        ContextMessage.assistantToolCalls(
                                List.of(
                                        new ContextToolCall(
                                                call,
                                                name,
                                                Map.of("queryId", ID, "buildId", branch)))),
                        ContextMessage.tool(
                                "{\"queryId\":\"call\",\"status\":\"" + status + "\"}",
                                call,
                                name)),
                HASH,
                true);
    }
}
