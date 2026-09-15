package com.datagent.agentv2.git;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.domain.event.ConversationMessageChanged;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitSessionResolver;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
import com.datagent.agentv2.skill.tool.SkillReadTool;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 真实 PostgreSQL、裸远端、Web port 与 MVC 验证授权和卡片原子登记。 */
@SpringBootTest(properties = "data-agent.git.enabled=true")
@AutoConfigureMockMvc
@Import(GitWebIntegrationTest.FailEvents.class)
class GitWebIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
    static final Path ROOT;

    static {
        try {
            DB.start();
            ROOT = Files.createTempDirectory("git-web-test-");
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
        r.add("data-agent.git.remote-uri", () -> ROOT.resolve("remote.git").toString());
        r.add("data-agent.git.workspace-root", () -> ROOT.resolve("data").toString());
    }

    @TestConfiguration
    static class FailEvents {
        final AtomicBoolean fail = new AtomicBoolean();

        @EventListener
        public void on(ConversationMessageChanged event) {
            if (fail.get()) throw new IllegalStateException("injected message event failure");
        }
    }

    @Autowired com.datagent.agentv2.conversation.react.ModelInvocationCoordinator coordinator;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitWorkspaceService workspaces;
    @Autowired GitChangeService changes;
    @Autowired List<AgentTool> registeredTools;
    @Autowired GitSessionResolver sessions;
    @Autowired FailEvents events;
    @Autowired ExecutionToolViews toolViews;
    @Autowired ExecutionSkillViews skillViews;
    @Autowired SkillReadTool skillRead;
    @MockitoBean ReactExecutionEngine engine;

    @AfterEach
    void resetEvents() {
        events.fail.set(false);
    }

    @AfterAll
    static void stop() throws Exception {
        DB.stop();
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    record Run(String session, String execution) {}

    Run execution() throws Exception {
        var s =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType("application/json")
                                        .content("{\"title\":\"Git test\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String session =
                json.readTree(s.getResponse().getContentAsString())
                        .path("conversationSessionId")
                        .asString();
        var e =
                mvc.perform(
                                post("/api/v1/agent-sessions/" + session + "/messages")
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content("{\"content\":\"修改指标\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn();
        return new Run(
                session,
                json.readTree(e.getResponse().getContentAsString())
                        .path("execution")
                        .path("executionId")
                        .asString());
    }

    long edit(Run r) throws Exception {
        var context =
                new AgentTool.InvocationContext(
                        "tenant_local", r.execution(), 1, "edit", UUID.randomUUID().toString());
        tool("git_workspace_prepare").execute(json.readTree("{}"), context);
        var result =
                tool("git_files_apply")
                        .execute(
                                json.valueToTree(
                                        Map.of(
                                                "operations",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "CREATE",
                                                                "path",
                                                                UUID.randomUUID() + ".yml",
                                                                "content",
                                                                "new\n")))),
                                context);
        return result.path("draftRevision").asLong();
    }

    AgentTool tool(String name) {
        return registeredTools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    GitChangeView candidate(Run r) throws Exception {
        long revision = edit(r);
        var tool = tool("git_change_prepare");
        var result =
                tool.execute(
                        json.valueToTree(
                                Map.of(
                                        "summary",
                                        "新增指标",
                                        "commitMessage",
                                        "add metric",
                                        "expectedRevision",
                                        revision)),
                        new AgentTool.InvocationContext(
                                "tenant_local",
                                r.execution(),
                                1,
                                "prepare",
                                UUID.randomUUID().toString()));
        return json.treeToValue(result, GitChangeView.class);
    }

    String base(Run r, GitChangeView c) {
        return "/api/v1/agent-sessions/" + r.session() + "/git-changes/" + c.changeId();
    }

    @Test
    void cardIncludesTrustedSessionAndConfirmIsVersionedAndIdempotent() throws Exception {
        Run r = execution();
        GitChangeView c = candidate(r);
        String base = base(r, c);
        var run =
                new com.datagent.agentv2.conversation.runtime.LocalAgentExecution(
                        "tenant_local", r.session(), r.execution(), 1);
        var boundary = coordinator.beginModelInvocation(run);
        coordinator.completeFinalAnswer(run, boundary, "请通过卡片确认变更。");
        String content =
                jdbc.queryForObject(
                        "select content from conversation_message where execution_id=? and role='assistant'",
                        String.class,
                        r.execution());
        assertThat(content).contains("请通过卡片确认变更。", "<agent-ui block-id=\"");
        assertThat(
                        jdbc.queryForObject(
                                "select answer from agent_execution_stream_checkpoint where execution_id=?",
                                String.class,
                                r.execution()))
                .isEqualTo(content);

        assertThat(sessions.resolveSessionId(r.execution())).isEqualTo(r.session());
        mvc.perform(get("/api/v1/agent-sessions/" + r.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].uiBlocks[0].type").value("git-change-card"))
                .andExpect(
                        jsonPath("$.messages[1].uiBlocks[0].payload.changeId").value(c.changeId()))
                .andExpect(
                        jsonPath("$.messages[1].uiBlocks[0].payload.sessionId").value(r.session()));
        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].fileId").value("0"))
                .andExpect(jsonPath("$.files[0].diff").doesNotExist());
        mvc.perform(get(base + "/diff").param("fileId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff").isString());
        mvc.perform(get("/api/v1/agent-sessions/other/git-changes/" + c.changeId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GIT_CHANGE_NOT_FOUND"));
        mvc.perform(
                        post(base + "/confirm")
                                .contentType("application/json")
                                .content("{\"version\":999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GIT_CONFLICT"));
        String request = "{\"version\":" + c.version() + "}";
        mvc.perform(post(base + "/confirm").contentType("application/json").content(request))
                .andExpect(status().isAccepted());
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(changes.detail(r.session(), c.changeId()).status())
                                        .isEqualTo(GitChangeStatus.PUSHED));
        String commit = changes.detail(r.session(), c.changeId()).commitId();
        mvc.perform(post(base + "/confirm").contentType("application/json").content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commitId").value(commit));
        assertThat(run(ROOT, "--git-dir=" + ROOT.resolve("remote.git"), "rev-parse", "main").trim())
                .isEqualTo(commit);
        assertThat(
                        run(
                                        ROOT,
                                        "--git-dir=" + ROOT.resolve("remote.git"),
                                        "rev-list",
                                        "--count",
                                        "main")
                                .trim())
                .isEqualTo("2");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from git_change where change_id=?",
                                Integer.class,
                                c.changeId()))
                .isOne();
        // 已推送的事实来自远端核实；之后审阅材料丢失或损坏不得遮蔽它。
        for (boolean missing : new boolean[] {true, false}) {
            damageSnapshot(c, missing);
            mvc.perform(get(base))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUSHED"))
                    .andExpect(jsonPath("$.commitId").value(commit))
                    .andExpect(jsonPath("$.reviewAvailable").value(false));
            mvc.perform(post(base + "/confirm").contentType("application/json").content(request))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PUSHED"))
                    .andExpect(jsonPath("$.commitId").value(commit));
            mvc.perform(
                            post(base + "/reconcile")
                                    .contentType("application/json")
                                    .content(
                                            "{\"version\":"
                                                    + changes.detail(r.session(), c.changeId())
                                                            .version()
                                                    + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUSHED"))
                    .andExpect(jsonPath("$.commitId").value(commit));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableReviewStillAllowsMetadataAndCancellation(boolean missing) throws Exception {
        Run r = execution();
        GitChangeView c = candidate(r);
        damageSnapshot(c, missing);
        mvc.perform(get(base(r, c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.reviewAvailable").value(false))
                .andExpect(jsonPath("$.files").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(
                        post(base(r, c) + "/cancel")
                                .contentType("application/json")
                                .content("{\"version\":" + c.version() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reviewAvailable").value(false));
        mvc.perform(get(base(r, c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedConfirmationRemainsQueryableWhenReviewIsUnavailable(boolean missing)
            throws Exception {
        Run r = execution();
        GitChangeView c = candidate(r);
        damageSnapshot(c, missing);
        mvc.perform(
                        post(base(r, c) + "/confirm")
                                .contentType("application/json")
                                .content("{\"version\":" + c.version() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GIT_SNAPSHOT_CORRUPT"));
        mvc.perform(get(base(r, c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("GIT_SNAPSHOT_CORRUPT"))
                .andExpect(jsonPath("$.reviewAvailable").value(false));
        mvc.perform(get("/api/v1/agent-sessions/other/git-changes/" + c.changeId()))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(r, c) + "/files"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GIT_SNAPSHOT_CORRUPT"));
    }

    void damageSnapshot(GitChangeView c, boolean missing) throws Exception {
        String key =
                jdbc.queryForObject(
                        "select snapshot_storage_key from git_change where change_id=?",
                        String.class,
                        c.changeId());
        Path snapshot = ROOT.resolve("data").resolve(key);
        if (missing) Files.delete(snapshot);
        else Files.writeString(snapshot, "corrupt");
    }

    @Test
    void publicationFailureRollsBackCandidateBlockPointerAndMessage() throws Exception {
        Run r = execution();
        long revision = edit(r);
        events.fail.set(true);
        assertThatThrownBy(
                        () ->
                                changes.prepare(
                                        r.session(),
                                        r.execution(),
                                        "rollback",
                                        "变更",
                                        "change",
                                        revision))
                .isInstanceOf(RuntimeException.class);
        events.fail.set(false);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from git_change where conversation_session_id=?",
                                Integer.class,
                                r.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select active_change_id from git_workspace where conversation_session_id=?",
                                String.class,
                                r.session()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from message_ui_block b join conversation_message m on m.conversation_message_id=b.conversation_message_id where m.conversation_session_id=?",
                                Integer.class,
                                r.session()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select content from conversation_message where execution_id=? and role='assistant'",
                                String.class,
                                r.execution()))
                .doesNotContain("agent-ui");
    }

    @Test
    void crossExecutionPublicationCannotAttachToAnotherSession() throws Exception {
        Run owner = execution();
        Run other = execution();
        long revision = edit(owner);
        assertThatThrownBy(
                        () ->
                                changes.prepare(
                                        owner.session(),
                                        other.execution(),
                                        "cross",
                                        "变更",
                                        "change",
                                        revision))
                .isInstanceOf(GitException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from git_change where conversation_session_id=?",
                                Integer.class,
                                owner.session()))
                .isZero();
    }

    @Test
    void gitToolsAppearOnlyAfterReadingSkill() throws Exception {
        String id = "activation-" + UUID.randomUUID();
        var tools = toolViews.open(id);
        skillViews.open(id);
        try {
            assertThat(tools.visibleNames()).doesNotContain("git_change_prepare");
            var result =
                    skillRead.execute(
                            json.readTree("{\"logical_id\":\"git-repository-maintenance\"}"),
                            new AgentTool.InvocationContext("tenant_local", id, 1, "read", null));
            assertThat(result.path("activatedTools").size()).isEqualTo(7);
            assertThat(tools.visibleNames()).contains("git_change_prepare");
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
