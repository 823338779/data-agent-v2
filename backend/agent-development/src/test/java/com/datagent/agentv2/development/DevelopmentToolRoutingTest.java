package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.DevelopmentWorkspaceService;
import com.datagent.agentv2.development.tool.*;
import com.datagent.agentv2.git.config.GitProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 迁移旧工具路由保障：真实执行归属决定会话，模型无法覆盖，修订仍必须匹配。 */
class DevelopmentToolRoutingTest {
    private final JsonMapper json = new JsonMapper();
    private final DevelopmentWorkspaceService files = mock(DevelopmentWorkspaceService.class);
    private final AnalysisSessionResolver sessions =
            context -> context.executionId().equals("a") ? "session-a" : "session-b";
    private final DevelopmentToolSupport support =
            new DevelopmentToolSupport(
                    files,
                    sessions,
                    new GitProperties(
                            "git",
                            "unused",
                            Path.of("unused"),
                            Duration.ofSeconds(15),
                            100000,
                            100000,
                            20,
                            100,
                            1000000,
                            2097152),
                    json);

    private InvocationContext invocation(String id) {
        return new InvocationContext("tenant", id, 1, "call", "key");
    }

    @org.junit.jupiter.api.BeforeEach
    void executeWorkspaceLockCallback() {
        when(files.locked(anyString(), any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
    }

    @Test
    void sameExecutionKeepsSessionAndOtherExecutionCannotReuseTarget() throws Exception {
        var tool = new DevelopmentFilesSearchTool(support);
        var args = json.readTree("{\"query\":\"metric\"}");
        tool.execute(args, invocation("a"));
        tool.execute(args, invocation("b"));
        tool.execute(args, invocation("a"));
        verify(files, times(2)).search("session-a", "", "metric");
        verify(files).search("session-b", "", "metric");
    }

    @Test
    void modelCannotOverrideFrozenTarget() {
        for (String key :
                java.util.List.of("workspaceId", "sessionId", "branchName", "repository")) {
            var args = json.createObjectNode().put("query", "metric").put(key, "attacker");
            assertThatThrownBy(
                            () ->
                                    new DevelopmentFilesSearchTool(support)
                                            .execute(args, invocation("a")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(files);
    }

    @Test
    void applyRequiresExpectedRevisionBeforeChangingFiles() {
        assertThatThrownBy(
                        () ->
                                new DevelopmentFilesApplyTool(support)
                                        .execute(
                                                json.readTree(
                                                        "{\"operations\":[{\"type\":\"CREATE\",\"path\":\"model.sql\",\"content\":\"select"
                                                                + " 1\"}]}"),
                                                invocation("a")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(files);
    }

    @Test
    void applyRoutesOnlyTrustedSessionAndPreservesRevision() throws Exception {
        when(files.apply(eq("session-a"), eq(3L), anyList())).thenReturn(4L);
        var result =
                new DevelopmentFilesApplyTool(support)
                        .execute(
                                json.readTree(
                                        "{\"expectedRevision\":3,\"operations\":[{\"type\":\"CREATE\",\"path\":\"model.sql\",\"content\":\"select"
                                                + " 1\"}]}"),
                                invocation("a"));
        assertThat(result.path("revision").asLong()).isEqualTo(4);
        verify(files).apply(eq("session-a"), eq(3L), anyList());
    }
}
