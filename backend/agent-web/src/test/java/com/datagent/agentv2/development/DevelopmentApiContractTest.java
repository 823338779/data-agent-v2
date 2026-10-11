package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.tool.*;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

/** 真实MVC映射与工具共用用例验证，取代已退役的工作区代理测试。 */
class DevelopmentApiContractTest {
    private static final String CONFIG_PATH = "/api/v1/agent-projects/p1/development-config";
    private static final String CONFIG_JSON =
            """
            {"repository":"repo","previewBinding":"preview","previewConfigVersion":"1",
             "previewSchemaName":"preview_metrics","productionBinding":"production",
             "productionConfigVersion":"2","productionSchemaName":"production_metrics","expectedVersion":3}
            """;
    private static final String PROJECT = "p1",
            PREVIEW_SCHEMA = "preview_metrics",
            PRODUCTION_SCHEMA = "production_metrics";
    private final SourceSaveService saves = mock(SourceSaveService.class);
    private final PreviewBuildService previews = mock(PreviewBuildService.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final DevelopmentStatusService statuses = mock(DevelopmentStatusService.class);
    private final DevelopmentRecoveryRunner recovery = mock(DevelopmentRecoveryRunner.class);
    private final DevelopmentWorkspaceService files = mock(DevelopmentWorkspaceService.class);
    private final DevelopmentBranchService branches = mock(DevelopmentBranchService.class);
    private final DevelopmentContextService contexts = mock(DevelopmentContextService.class);
    private final ObjectMapper json = new ObjectMapper();

    private <T> org.springframework.beans.factory.ObjectProvider<T> provider(
            Class<T> type, T value) {
        var beans = new StaticListableBeanFactory();
        beans.addBean(type.getName(), value);
        return beans.getBeanProvider(type);
    }

    private DevelopmentSessionController sessionController() {
        return new DevelopmentSessionController(
                saves,
                provider(PreviewBuildService.class, previews),
                provider(DeliveryService.class, deliveries),
                provider(DevelopmentStatusService.class, statuses),
                recovery,
                files);
    }

    private DevelopmentProjectController projectController() {
        return new DevelopmentProjectController(
                mock(ConversationProjectService.class),
                mock(DevelopmentContextPersistenceService.class),
                contexts,
                provider(DevelopmentBranchService.class, branches));
    }

    @Test
    void projectConfigurationPreservesBothExplicitSchemaNames() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(projectController()).build();
        mvc.perform(
                        put(CONFIG_PATH)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(CONFIG_JSON))
                .andExpect(status().isOk());
        var captured = org.mockito.ArgumentCaptor.forClass(DevelopmentConfig.class);
        verify(contexts).configure(eq(PROJECT), captured.capture(), eq(3L));
        assertThat(captured.getValue().previewSchemaName()).isEqualTo(PREVIEW_SCHEMA);
        assertThat(captured.getValue().productionSchemaName()).isEqualTo(PRODUCTION_SCHEMA);
    }

    @Test
    void httpAndModelSaveUseIdenticalApplicationRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(sessionController()).build();
        mvc.perform(
                        post("/api/v1/agent-sessions/s1/development/save")
                                .contentType("application/json")
                                .content(
                                        "{\"idempotencyKey\":\"trusted-key\",\"expectedRevision\":3,\"commitMessage\":\"save\"}"))
                .andExpect(status().isAccepted());
        var tool = new DevelopmentSaveTool(saves, invocation -> "s1", json);
        tool.execute(
                json.readTree("{\"expectedRevision\":3,\"commitMessage\":\"save\"}"),
                new InvocationContext("tenant", "execution", 1, "call", "trusted-key"));
        verify(saves, times(2)).start("s1", new SaveRequest("trusted-key", 3, "save"));
    }

    @Test
    void branchReadsUseGitAndWritesRejectTargetOverride() throws Exception {
        when(branches.list("p1"))
                .thenReturn(
                        List.of(
                                new com.datagent.agentv2.git.model.BranchInfo(
                                        "dev/a", "a".repeat(40))));
        var mvc = MockMvcBuilders.standaloneSetup(projectController()).build();
        mvc.perform(get("/api/v1/agent-projects/p1/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].branchName").value("dev/a"));
        mvc.perform(
                        post("/api/v1/agent-projects/p1/branches")
                                .contentType("application/json")
                                .content("{\"branchName\":\"dev/new\",\"idempotencyKey\":\"key\"}"))
                .andExpect(status().isAccepted());
        verify(branches).create("p1", new BranchRequest("dev/new", "key"));
        mvc.perform(
                        post("/api/v1/agent-projects/p1/branches")
                                .contentType("application/json")
                                .content(
                                        "{\"branchName\":\"dev/new\",\"idempotencyKey\":\"key\",\"repository\":\"attacker\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(saves, previews, deliveries);
    }

    @Test
    void operationOwnershipAndRecoveryVersionReachSameUseCase() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(sessionController()).build();
        var id = UUID.randomUUID();
        mvc.perform(get("/api/v1/agent-sessions/s1/development/operations/" + id))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/agent-sessions/s1/development/operations/" + id + "/recover")
                                .contentType("application/json")
                                .content("{\"expectedVersion\":5,\"idempotencyKey\":\"recover\"}"))
                .andExpect(status().isAccepted());
        verify(recovery).getOperation("s1", id);
        verify(recovery).recover("s1", id, new RecoverRequest(5, "recover"));
        verifyNoInteractions(saves, previews, deliveries);
    }

    @Test
    void everyDevelopmentMappingExistsInPackagedRuntimeContract() throws Exception {
        var contract = new DevelopmentContractController();
        var mvc =
                MockMvcBuilders.standaloneSetup(sessionController(), projectController(), contract)
                        .build();
        var response =
                mvc.perform(get("/api/v1/development/contract"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.protocolVersion").value("build-deployment-v3"))
                        .andReturn();
        var paths = json.readTree(response.getResponse().getContentAsString()).path("paths");
        var mappings =
                mvc.getDispatcherServlet()
                        .getWebApplicationContext()
                        .getBean(RequestMappingHandlerMapping.class)
                        .getHandlerMethods();
        mappings.forEach(
                (mapping, handler) ->
                        mapping.getPatternValues()
                                .forEach(
                                        path ->
                                                mapping.getMethodsCondition()
                                                        .getMethods()
                                                        .forEach(
                                                                method ->
                                                                        assertThat(
                                                                                        paths.path(
                                                                                                        path)
                                                                                                .has(
                                                                                                        method.name()
                                                                                                                .toLowerCase(
                                                                                                                        Locale
                                                                                                                                .ROOT)))
                                                                                .as(
                                                                                        method + " "
                                                                                                + path)
                                                                                .isTrue())));
        for (String retired :
                List.of(
                        "/api/v1/git-workspaces",
                        "/api/v1/metric-branches",
                        "/api/v1/agent-sessions/s1/git-changes/c1/confirm"))
            mvc.perform(post(retired).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
    }

    @Test
    void fileSnapshotAndRevisionShareLockAndMalformedOperationsNeverReachWorkspace()
            throws Exception {
        var held = new java.util.concurrent.atomic.AtomicBoolean();
        when(files.locked(anyString(), any()))
                .thenAnswer(
                        call -> {
                            held.set(true);
                            try {
                                return ((java.util.function.Supplier<?>) call.getArgument(1)).get();
                            } finally {
                                held.set(false);
                            }
                        });
        when(files.list("s1", "."))
                .thenAnswer(
                        call -> {
                            assertThat(held).isTrue();
                            return List.of("model.sql");
                        });
        when(files.getRevision("s1"))
                .thenAnswer(
                        call -> {
                            assertThat(held).isTrue();
                            return 7L;
                        });
        when(files.diff("s1"))
                .thenAnswer(
                        call -> {
                            assertThat(held).isTrue();
                            return new com.datagent.agentv2.git.model.GitSnapshot(
                                    "tree", "digest", "diff", List.of());
                        });
        var mvc = MockMvcBuilders.standaloneSetup(sessionController()).build();
        mvc.perform(get("/api/v1/agent-sessions/s1/development/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(7));
        mvc.perform(get("/api/v1/agent-sessions/s1/development/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(7));
        for (String operations :
                List.of(
                        "[null]",
                        "[{\"type\":null,\"path\":\"model.sql\"}]",
                        "[{\"type\":\"CREATE\",\"path\":null}]")) {
            mvc.perform(
                            post("/api/v1/agent-sessions/s1/development/files")
                                    .contentType("application/json")
                                    .content(
                                            "{\"expectedRevision\":7,\"operations\":"
                                                    + operations
                                                    + "}"))
                    .andExpect(status().isBadRequest());
        }
        verify(files, never()).apply(anyString(), anyLong(), any());
    }
}
