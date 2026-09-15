package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.project.ConversationOrganizationService;
import com.datagent.agentv2.conversation.project.ConversationProjectApplicationService;
import com.datagent.agentv2.conversation.project.ConversationSidebarQueryService;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import java.time.Duration;
import java.util.concurrent.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationOrganizationIntegrationTest {
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationProjectApplicationService projects;
    @MockitoSpyBean ConversationProjectService projectStore;
    @Autowired ConversationSidebarQueryService sidebar;
    @Autowired ConversationOrganizationService organizations;
    @Autowired ConversationApplicationService conversations;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ReactExecutionEngine executionEngine;

    private JsonNode createProject() throws Exception {
        return json.readTree(
                mvc.perform(
                                post("/api/v1/agent-projects")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"  分析  \"}"))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.name").value("分析"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void explicitNullVersionAndPinAndNegativeVersionsAreRejected() throws Exception {
        var session = conversations.create("request validation", null);
        String path = "/api/v1/agent-sessions/" + session.conversationSessionId() + "/organization";
        for (String invalid :
                new String[] {
                    "{\"projectId\":null,\"pinned\":null,\"expectedVersion\":0}",
                    "{\"projectId\":null,\"pinned\":false,\"expectedVersion\":null}",
                    "{\"projectId\":null,\"pinned\":false,\"expectedVersion\":-1}"
                }) {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"projectId\":\"missing\",\"pinned\":false,\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/agent-sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"projectId\":\"missing\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationLifecycleKeepsSessionsAndRejectsIncompleteRequests() throws Exception {
        var project = createProject();
        String id = project.path("projectId").asString();
        var session =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/agent-sessions")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"title\":\"报告\",\"projectId\":\""
                                                                + id
                                                                + "\"}"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.projectId").value(id))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String sid = session.path("conversationSessionId").asString();
        String path = "/api/v1/agent-sessions/" + sid + "/organization";
        for (String invalid :
                new String[] {
                    "{}",
                    "{\"pinned\":false,\"expectedVersion\":0}",
                    "{\"projectId\":null,\"expectedVersion\":0}",
                    "{\"projectId\":null,\"pinned\":false}",
                    "{\"projectId\":\"\",\"pinned\":false,\"expectedVersion\":0}"
                }) {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"projectId\":\""
                                                + id
                                                + "\",\"pinned\":true,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedAt").isNotEmpty())
                .andExpect(jsonPath("$.messages").doesNotExist());
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"projectId\":null,\"pinned\":false,\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/agent-projects/" + id).param("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/agent-sessions/" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").isEmpty())
                .andExpect(jsonPath("$.pinnedAt").isNotEmpty());
        mvc.perform(get("/api/v1/agent-sidebar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isArray())
                .andExpect(jsonPath("$.sessions").isArray());
        mvc.perform(
                        put(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"projectId\":null,\"pinned\":false,\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedAt").isEmpty());
    }

    @Test
    void deletionRollsBackAssociationWhenOuterTransactionFails() throws Exception {
        var project = projects.create("rollback");
        var created = conversations.create("kept", null);
        organizations.setOrganization(
                created.conversationSessionId(), project.projectId(), true, created.version());
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    projects.delete(
                                                            project.projectId(), project.version());
                                                    throw new IllegalStateException("rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(projectStore.listByCreatedAtAsc())
                .extracting(p -> p.projectId())
                .contains(project.projectId());
        assertThat(
                        jdbc.queryForObject(
                                "select project_id from conversation_session where conversation_session_id=?",
                                String.class,
                                created.conversationSessionId()))
                .isEqualTo(project.projectId());
    }

    @Test
    void moveWaitsForDeletionAndCannotAttachToDeletedProject() throws Exception {
        var project = projects.create("concurrent");
        var created = conversations.create("kept", null);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deleting =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        projects.delete(project.projectId(), 0);
                                                        acquired.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "release timeout");
                                                        } catch (InterruptedException failure) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(failure);
                                                        }
                                                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            var moving =
                    executor.submit(
                            () ->
                                    organizations.setOrganization(
                                            created.conversationSessionId(),
                                            project.projectId(),
                                            false,
                                            created.version()));
            var creating =
                    executor.submit(
                            () ->
                                    conversations.create(
                                            "concurrent new", null, project.projectId()));
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(5))
                        .until(
                                () ->
                                        Boolean.TRUE.equals(
                                                jdbc.queryForObject(
                                                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%conversation_project%')",
                                                        Boolean.class)));
            } finally {
                release.countDown();
            }
            deleting.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> moving.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .satisfies(
                            e ->
                                    assertThat(
                                                    ((ResponseStatusException) e.getCause())
                                                            .getStatusCode()
                                                            .value())
                                            .isEqualTo(404));
            assertThatThrownBy(() -> creating.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .satisfies(
                            e ->
                                    assertThat(
                                                    ((ResponseStatusException) e.getCause())
                                                            .getStatusCode()
                                                            .value())
                                            .isEqualTo(404));
        }
    }

    @Test
    void deletionWaitsForNewSessionAndDetachesItAfterCommit() throws Exception {
        var project = projects.create("create first");
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creating =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    tx -> {
                                                        var session =
                                                                conversations.create(
                                                                        "new",
                                                                        null,
                                                                        project.projectId());
                                                        acquired.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "release timeout");
                                                        } catch (InterruptedException failure) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(failure);
                                                        }
                                                        return session;
                                                    }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            var deleting = executor.submit(() -> projects.delete(project.projectId(), 0));
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(5))
                        .until(
                                () ->
                                        Boolean.TRUE.equals(
                                                jdbc.queryForObject(
                                                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%conversation_project%')",
                                                        Boolean.class)));
            } finally {
                release.countDown();
            }
            var session = creating.get(5, TimeUnit.SECONDS);
            deleting.get(5, TimeUnit.SECONDS);
            assertThat(conversations.get(session.conversationSessionId()).projectId()).isNull();
        }
    }

    @Test
    void sidebarKeepsOneSnapshotAcrossConcurrentProjectDeletion() throws Exception {
        var project = projects.create("snapshot");
        var session = conversations.create("same snapshot", null, project.projectId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            doAnswer(
                            invocation -> {
                                var result = invocation.callRealMethod();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "show transaction_isolation", String.class))
                                        .isEqualTo("repeatable read");
                                executor.submit(() -> projects.delete(project.projectId(), 0))
                                        .get(5, TimeUnit.SECONDS);
                                return result;
                            })
                    .when(projectStore)
                    .listByCreatedAtAsc();
            var snapshot = sidebar.getSidebar();
            assertThat(snapshot.projects())
                    .extracting(p -> p.projectId())
                    .contains(project.projectId());
            assertThat(snapshot.sessions())
                    .filteredOn(
                            s -> s.conversationSessionId().equals(session.conversationSessionId()))
                    .singleElement()
                    .satisfies(s -> assertThat(s.projectId()).isEqualTo(project.projectId()));
        } finally {
            reset(projectStore);
        }
        assertThat(conversations.get(session.conversationSessionId()).projectId()).isNull();
    }

    @Test
    void projectRenameValidatesVersionAndDeletionPreservesActiveExecution() throws Exception {
        var project = projects.create("running");
        String projectPath = "/api/v1/agent-projects/" + project.projectId();
        mvc.perform(
                        patch(projectPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\" 新名字 \",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名字"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(delete(projectPath).param("expectedVersion", "0"))
                .andExpect(status().isConflict());
        mvc.perform(delete(projectPath)).andExpect(status().isBadRequest());
        var session = conversations.create("running session", null, project.projectId());
        var submitted =
                conversations.submitMessage(
                        session.conversationSessionId(),
                        "继续分析",
                        "grouping-test-" + project.projectId());
        var before = conversations.get(session.conversationSessionId());
        assertThat(before.execution()).isNotNull();
        projects.delete(project.projectId(), 1);
        var after = conversations.get(session.conversationSessionId());
        assertThat(after.projectId()).isNull();
        assertThat(after.messages()).isEqualTo(before.messages());
        assertThat(after.execution()).isEqualTo(before.execution());
        assertThat(after.execution().executionId()).isEqualTo(submitted.execution().executionId());
        assertThat(after.execution().status())
                .isEqualTo(com.datagent.agentv2.conversation.enums.ExecutionStatus.RUNNING);
    }
}
