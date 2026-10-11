package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
import com.datagent.agentv2.skill.tool.SkillReadTool;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真正的HTTP、数据库、裸Git及受管后台恢复，验证新版固定目标保存流程。 */
@SpringBootTest(
        properties = {
            "data-agent.git.enabled=true",
            "data-agent.development.recovery-delay-ms=100"
        })
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class DevelopmentWebIntegrationTest {
    private static final String PREVIEW_SCHEMA = "preview_metrics",
            PRODUCTION_SCHEMA = "production_metrics";
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
    static final Path ROOT;

    static {
        try {
            DB.start();
            ROOT = Files.createTempDirectory("development-web-test-");
            run(
                    ROOT,
                    "init",
                    "--bare",
                    "--initial-branch=main",
                    ROOT.resolve("remote.git").toString());
            run(
                    ROOT,
                    "clone",
                    ROOT.resolve("remote.git").toString(),
                    ROOT.resolve("seed").toString());
            Files.writeString(ROOT.resolve("seed/metrics.yml"), "old\n");
            run(ROOT.resolve("seed"), "add", ".");
            run(
                    ROOT.resolve("seed"),
                    "-c",
                    "user.name=seed",
                    "-c",
                    "user.email=seed@test",
                    "commit",
                    "-m",
                    "seed");
            run(ROOT.resolve("seed"), "push", "origin", "main");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("data-agent.git.workspace-root", () -> ROOT.resolve("data").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<AgentTool> registeredTools;
    @Autowired ExecutionToolViews toolViews;
    @Autowired ExecutionSkillViews skillViews;
    @Autowired SkillReadTool skillRead;
    @Autowired com.datagent.agentv2.development.application.DevelopmentContextService contexts;

    @Autowired
    com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService
            developmentRecords;

    @Autowired com.datagent.agentv2.persistence.service.AgentExecutionService executions;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.datagent.agentv2.development.application.DevelopmentBranchService branches;

    @MockitoBean ReactExecutionEngine engine;

    @Autowired org.springframework.context.ConfigurableApplicationContext application;

    @AfterAll
    void stop() throws Exception {
        application
                .getBeansOfType(
                        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                .values()
                .forEach(
                        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
                                ::shutdown);
        DB.stop();
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    record Run(String project, String session, String branch, String execution) {}

    Run execution() throws Exception {
        var project =
                postJson("/api/v1/agent-projects", Map.of("name", "开发工程"), 201)
                        .path("projectId")
                        .asString();
        var configuration =
                Map.of(
                        "repository",
                        ROOT.resolve("remote.git").toString(),
                        "previewBinding",
                        "preview",
                        "previewConfigVersion",
                        "v1",
                        "previewSchemaName",
                        PREVIEW_SCHEMA,
                        "productionBinding",
                        "prod",
                        "productionConfigVersion",
                        "v1",
                        "productionSchemaName",
                        PRODUCTION_SCHEMA,
                        "expectedVersion",
                        0);
        mvc.perform(
                        put("/api/v1/agent-projects/" + project + "/development-config")
                                .contentType("application/json")
                                .content(json.writeValueAsString(configuration)))
                .andExpect(status().isOk());
        String branch = "dev/" + UUID.randomUUID();
        String main = run(ROOT.resolve("seed"), "rev-parse", "HEAD").trim();
        run(
                ROOT,
                "--git-dir=" + ROOT.resolve("remote.git"),
                "update-ref",
                "refs/heads/" + branch,
                main);
        String session =
                postJson(
                                "/api/v1/agent-sessions",
                                Map.of(
                                        "title",
                                        "开发会话",
                                        "projectId",
                                        project,
                                        "development",
                                        Map.of("projectId", project, "branchName", branch)),
                                201)
                        .path("conversationSessionId")
                        .asString();
        String accepted =
                mvc.perform(
                                post("/api/v1/agent-sessions/" + session + "/messages")
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content("{\"content\":\"开发工程\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Run(
                project,
                session,
                branch,
                json.readTree(accepted).path("execution").path("executionId").asString());
    }

    JsonNode postJson(String path, Object body, int status) throws Exception {
        return json.readTree(
                mvc.perform(
                                post(path)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    String base(Run run) {
        return "/api/v1/agent-sessions/" + run.session() + "/development";
    }

    AgentTool tool(String name) {
        return registeredTools.stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void fixedSessionFilesSaveAndBackgroundCompletionSurviveWithoutChatConnection()
            throws Exception {
        Run r = execution();
        var file =
                json.readTree(
                        mvc.perform(get(base(r) + "/file").param("path", "metrics.yml"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        var edits =
                Map.of(
                        "expectedRevision",
                        0,
                        "operations",
                        List.of(
                                Map.of(
                                        "type",
                                        "UPDATE",
                                        "path",
                                        "metrics.yml",
                                        "content",
                                        "invalid yaml: [\n",
                                        "expectedSha256",
                                        file.path("sha256").asString())));
        assertThat(postJson(base(r) + "/files", edits, 200).path("revision").asLong()).isEqualTo(1);
        mvc.perform(
                        post(base(r) + "/files")
                                .contentType("application/json")
                                .content(json.writeValueAsString(edits)))
                .andExpect(status().isConflict());
        String key = "save-" + UUID.randomUUID();
        var accepted =
                postJson(
                        base(r) + "/save",
                        Map.of(
                                "idempotencyKey",
                                key,
                                "expectedRevision",
                                1,
                                "commitMessage",
                                "save entire project"),
                        202);
        String operation = accepted.path("operationId").asString();
        var replay =
                tool("development_save")
                        .execute(
                                json.readTree(
                                        "{\"expectedRevision\":1,\"commitMessage\":\"save entire"
                                                + " project\"}"),
                                new AgentTool.InvocationContext(
                                        "tenant_local", r.execution(), 1, "save", key));
        assertThat(replay.path("operationId").asString()).isEqualTo(operation);
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                mvc.perform(get(base(r) + "/operations/" + operation))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                                        .andExpect(jsonPath("$.phase").value("PUSHED")));
        assertThat(
                        run(
                                ROOT,
                                "--git-dir=" + ROOT.resolve("remote.git"),
                                "show",
                                r.branch() + ":metrics.yml"))
                .isEqualTo("invalid yaml: [\n");
        assertThat(
                        run(
                                        ROOT,
                                        "--git-dir=" + ROOT.resolve("remote.git"),
                                        "rev-list",
                                        "--count",
                                        r.branch())
                                .trim())
                .isEqualTo("2");
        assertThat(
                        run(
                                        ROOT,
                                        "--git-dir=" + ROOT.resolve("remote.git"),
                                        "rev-list",
                                        "--count",
                                        "main")
                                .trim())
                .isEqualTo("1");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from development_operation where session_id=?",
                                Integer.class,
                                r.session()))
                .isEqualTo(1);
        Run other = execution();
        mvc.perform(get(base(other) + "/operations/" + operation)).andExpect(status().isNotFound());
    }

    @Test
    void newSessionBindingIsAtomicAndCannotBeChangedThroughMessages() throws Exception {
        Run r = execution();
        assertThat(
                        jdbc.queryForObject(
                                "select branch_name from development_session where session_id=?",
                                String.class,
                                r.session()))
                .isEqualTo(r.branch());
        mvc.perform(
                        post("/api/v1/agent-sessions/" + r.session() + "/messages")
                                .header("Idempotency-Key", "override")
                                .contentType("application/json")
                                .content("{\"content\":\"override\",\"workspaceId\":\"another\"}"))
                .andExpect(status().isBadRequest());
        int before =
                jdbc.queryForObject("select count(*) from conversation_session", Integer.class);
        mvc.perform(
                        post("/api/v1/agent-sessions")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "title",
                                                        "bad",
                                                        "development",
                                                        Map.of(
                                                                "projectId",
                                                                r.project(),
                                                                "branchName",
                                                                "missing")))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from conversation_session", Integer.class))
                .isEqualTo(before);
        mvc.perform(post("/api/v1/git-workspaces").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/agent-sessions/" + r.session() + "/git-changes/old/confirm")
                                .contentType("application/json")
                                .content("{\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void configurationChangeDuringBranchReadRollsBackSessionCreation() throws Exception {
        String project =
                postJson("/api/v1/agent-projects", Map.of("name", "配置竞态"), 201)
                        .path("projectId")
                        .asString();
        var original =
                new com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentConfig(
                        ROOT.resolve("remote.git").toString(),
                        "preview",
                        "v1",
                        PREVIEW_SCHEMA,
                        "production",
                        "v1",
                        PRODUCTION_SCHEMA,
                        0);
        contexts.configure(project, original, 0);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            contexts.configure(
                                    project,
                                    new com.datagent.agentv2.development.model.DevelopmentContracts
                                            .DevelopmentConfig(
                                            original.repository(),
                                            "preview-changed",
                                            "v2",
                                            original.previewSchemaName(),
                                            "production",
                                            "v1",
                                            original.productionSchemaName(),
                                            1),
                                    1);
                            return result;
                        })
                .when(branches)
                .list(project);
        String branch = "dev/" + UUID.randomUUID();
        run(
                ROOT,
                "--git-dir=" + ROOT.resolve("remote.git"),
                "update-ref",
                "refs/heads/" + branch,
                run(ROOT.resolve("seed"), "rev-parse", "HEAD").trim());
        int before =
                jdbc.queryForObject("select count(*) from conversation_session", Integer.class);
        mvc.perform(
                        post("/api/v1/agent-sessions")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "title",
                                                        "race",
                                                        "development",
                                                        Map.of(
                                                                "projectId",
                                                                project,
                                                                "branchName",
                                                                branch)))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from conversation_session", Integer.class))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from development_session where project_id=?",
                                Integer.class,
                                project))
                .isZero();
    }

    @Test
    void runningAnalysisKeepsPersistentBuildAfterPreviewChangesSessionSelection() throws Exception {
        Run r = execution();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        jdbc.update(
                "update development_session set selected_build_id=? where session_id=?",
                first.toString(),
                r.session());
        var client =
                org.mockito.Mockito.mock(com.datagent.agentv2.dbt.client.DbtServiceClient.class);
        var resolver =
                new com.datagent.agentv2.development.application.DevelopmentBuildContextResolver(
                        developmentRecords, executions, client, "");
        assertThat(resolver.resolve(r.session(), r.execution())).isEqualTo(first);
        jdbc.update(
                "update development_session set selected_build_id=? where session_id=?",
                second.toString(),
                r.session());
        var restored =
                new com.datagent.agentv2.development.application.DevelopmentBuildContextResolver(
                        developmentRecords, executions, client, "");
        assertThat(restored.resolve(r.session(), r.execution())).isEqualTo(first);
        mvc.perform(
                        post(
                                "/api/v1/agent-sessions/"
                                        + r.session()
                                        + "/executions/"
                                        + r.execution()
                                        + ":cancel"))
                .andExpect(status().isAccepted());
        String accepted =
                mvc.perform(
                                post("/api/v1/agent-sessions/" + r.session() + "/messages")
                                        .header("Idempotency-Key", "next-analysis")
                                        .contentType("application/json")
                                        .content("{\"content\":\"分析新预览\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String next = json.readTree(accepted).path("execution").path("executionId").asString();
        assertThat(restored.resolve(r.session(), next)).isEqualTo(second);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void fileAndSaveToolsAppearOnlyAfterReadingSkill() throws Exception {
        String id = "activation-" + UUID.randomUUID();
        var tools = toolViews.open(id);
        skillViews.open(id);
        try {
            assertThat(tools.visibleNames()).doesNotContain("development_save", "git_files_apply");
            var result =
                    skillRead.execute(
                            json.readTree("{\"logical_id\":\"git-repository-maintenance\"}"),
                            new AgentTool.InvocationContext("tenant_local", id, 1, "read", null));
            assertThat(result.path("activatedTools").size()).isEqualTo(6);
            assertThat(tools.visibleNames())
                    .contains("development_save", "git_files_apply")
                    .doesNotContain("git_change_prepare", "git_workspace_prepare");
        } finally {
            toolViews.close(id);
            skillViews.close(id);
        }
    }

    static String run(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p =
                new ProcessBuilder(command)
                        .directory(cwd.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }
}
