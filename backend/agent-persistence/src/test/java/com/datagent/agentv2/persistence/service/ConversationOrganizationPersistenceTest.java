package com.datagent.agentv2.persistence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import com.datagent.agentv2.persistence.json.ConversationProcessDocumentCodec;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapperImpl;
import com.datagent.agentv2.persistence.mapping.ConversationProjectPersistenceMapperImpl;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({
    ConversationSessionService.class,
    ConversationProjectService.class,
    ConversationPersistenceMapperImpl.class,
    ConversationProjectPersistenceMapperImpl.class,
    ConversationProcessDocumentCodec.class
})
class ConversationOrganizationPersistenceTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConversationSessionService sessions;
    @Autowired private ConversationProjectService projects;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        projects.insertProject(ConversationProject.create("p1", " Project "));
        jdbc.update(
                "insert into conversation_session(conversation_session_id,tenant_id,title) values ('s1','tenant_local','Session')");
    }

    @Test
    void nullableUpdatesAndVersionsArePersisted() {
        var at = Instant.parse("2026-09-15T01:00:00Z");
        assertThat(sessions.updateOrganization("s1", "p1", at, 0)).isTrue();
        assertThat(sessions.findByUuid("s1").getPinnedAt()).isEqualTo(at);
        assertThat(sessions.updateOrganization("s1", null, null, 1)).isTrue();
        assertThat(sessions.updateOrganization("s1", "p1", at, 0)).isFalse();
        var loaded = sessions.findByUuid("s1");
        assertThat(loaded.getProjectId()).isNull();
        assertThat(loaded.getPinnedAt()).isNull();
        assertThat(loaded.getVersion()).isEqualTo(2);
    }

    @Test
    void ordinaryUpdateCannotRestoreOldOrganizationAndDetachKeepsPins() {
        var before = sessions.findByUuid("s1");
        var at = Instant.parse("2026-09-15T01:00:00Z");
        sessions.updateOrganization("s1", "p1", at, 0);
        before.acknowledgePersistence(1L, before.getCreatedAt(), before.getUpdatedAt());
        before.rename("Renamed");
        assertThat(sessions.updateById(before)).isTrue();
        assertThat(sessions.findByUuid("s1").getProjectId()).isEqualTo("p1");
        assertThat(sessions.detachProject("p1")).isEqualTo(1);
        var loaded = sessions.findByUuid("s1");
        assertThat(loaded.getProjectId()).isNull();
        assertThat(loaded.getPinnedAt()).isEqualTo(at);
        assertThat(loaded.getVersion()).isEqualTo(3);
        assertThat(loaded.getTitle()).isEqualTo("Renamed");
    }

    @Test
    void projectNamesAndLogicalDeletionFollowExistingAuditing() {
        var project = projects.listByCreatedAtAsc().getFirst();
        assertThat(project.name()).isEqualTo("Project");
        assertThat(project.createdAt()).isNotNull();
        assertThat(projects.renameProject("p1", "Renamed", 0)).isTrue();
        assertThat(projects.deleteProject("p1", 0)).isFalse();
        assertThat(projects.deleteProject("p1", 1)).isTrue();
        assertThat(projects.listByCreatedAtAsc()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select deleted_at is not null and is_deleted from conversation_project where project_id='p1'",
                                Boolean.class))
                .isTrue();
        assertThatThrownBy(() -> ConversationProject.create("bad", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationProject.create("bad", "a".repeat(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
