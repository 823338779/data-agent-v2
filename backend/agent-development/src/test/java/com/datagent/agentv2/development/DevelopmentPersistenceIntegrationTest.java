package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.persistence.AuditMetaObjectHandler;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.persistence.entity.development.DevelopmentProjectEntity;
import com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 真实数据库验证项目版本、固定会话目标和删除后的历史保留。 */
@SpringBootTest(
        classes = DevelopmentPersistenceIntegrationTest.Config.class,
        properties = "data-agent.context.strategy=none")
class DevelopmentPersistenceIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Configuration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        AuditMetaObjectHandler.class,
        DevelopmentContextPersistenceService.class,
        com.datagent.agentv2.persistence.service.development.DevelopmentOperationPersistenceService
                .class
    })
    static class Config {}

    @Autowired DevelopmentContextPersistenceService records;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    com.datagent.agentv2.persistence.service.development.DevelopmentOperationPersistenceService
            operations;

    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void legacyMigrationDryRunRollsBackAndRepeatedCommitPreservesIdentity() {
        String project = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String pushedSha = "a".repeat(40);
        jdbc.update(
                "insert into tenant(tenant_id,name) values('tenant_local','Local') on conflict do nothing");
        jdbc.update(
                "insert into conversation_project(tenant_id,project_id,name) values('tenant_local',?,?)",
                project,
                project);
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title,project_id) values('tenant_local',?,?,?)",
                session,
                session,
                project);
        var config = new DevelopmentProjectEntity();
        config.setProjectId(project);
        config.setRepository("repository-a");
        config.setConfigJson("{}");
        config.setEnabled(true);
        config.setVersion(0L);
        records.configure(config, 0);
        jdbc.update(
                "insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,git_ref) values(?,?,?,'READY','refs/heads/feature/shared')",
                workspace,
                session,
                "old-a");
        jdbc.update(
                "insert into git_session_workspace(conversation_session_id,workspace_id) values(?,?)",
                session,
                workspace);
        jdbc.update(
                "insert into git_change(change_id,workspace_id,conversation_session_id,repository_id,status,commit_id) values(?,?,?,?,'PUSHED',?)",
                UUID.randomUUID().toString(),
                workspace,
                session,
                "old-a",
                pushedSha);
        var mappings = java.util.Map.of("old-a", "repository-a");

        // 与一次性迁移入口使用同一事务边界；dry-run确实执行转换后回滚。
        var reports =
                transactions.execute(
                        status -> {
                            var result = records.migrateLegacy(project, mappings);
                            assertThat(records.session(session).getPushedSha())
                                    .isEqualTo(pushedSha);
                            status.setRollbackOnly();
                            return result;
                        });
        assertThat(reports)
                .singleElement()
                .satisfies(report -> assertThat(report.status()).isEqualTo("MIGRATED"));
        assertThat(records.session(session)).isNull();
        records.migrateLegacy(project, mappings);
        var migrated = records.session(session);
        records.migrateLegacy(project, mappings);
        assertThat(records.session(session).getId()).isEqualTo(migrated.getId());
        assertThat(records.session(session).getStorageKey()).isEqualTo(migrated.getStorageKey());
        assertThat(records.session(session).getPushedSha()).isEqualTo(pushedSha);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from git_change where conversation_session_id=?",
                                Integer.class,
                                session))
                .isEqualTo(1);
    }

    @Test
    void explicitRecoveryKeepsReceiptAndRetriesBuildOnlyOnce() {
        var row =
                new com.datagent.agentv2.persistence.entity.development
                        .DevelopmentOperationEntity();
        row.setOperationId(UUID.randomUUID().toString());
        row.setSessionId(UUID.randomUUID().toString());
        row.setProjectId(UUID.randomUUID().toString());
        row.setType("PREVIEW");
        row.setIdempotencyKey("preview");
        row.setRequestDigest("digest");
        row.setRequestJson("{}");
        row.setContextJson("{}");
        row.setPhase("BUILD_RUNNING");
        row.setStatus("FAILED");
        row.setVersion(0L);
        row.setAttemptNumber(0);
        row.setBuildId(UUID.randomUUID().toString());
        row.setPushedSha("a".repeat(40));
        operations.accept(row);
        var retried = operations.recover(row.getOperationId(), 0, "recovery", "BUILD_SUBMITTING");
        assertThat(retried.getAttemptNumber()).isEqualTo(1);
        assertThat(retried.getBuildId()).isNull();
        assertThat(retried.getPushedSha()).isEqualTo(row.getPushedSha());
        assertThat(
                        operations
                                .recover(row.getOperationId(), 0, "recovery", "BUILD_SUBMITTING")
                                .getAttemptNumber())
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                operations.recover(
                                        row.getOperationId(), 0, "another", "BUILD_SUBMITTING"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                operations.recover(
                                        row.getOperationId(), 1, "recovery", "BUILD_SUBMITTING"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyMultiRepositoryProjectIsReported() {
        String project = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        jdbc.update(
                "insert into tenant(tenant_id,name) values('tenant_local','Local') on conflict do nothing");
        jdbc.update(
                "insert into conversation_project(tenant_id,project_id,name) values('tenant_local',?,?)",
                project,
                project);
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title,project_id) values('tenant_local',?,?,?)",
                session,
                session,
                project);
        var config = new DevelopmentProjectEntity();
        config.setProjectId(project);
        config.setRepository("repository-a");
        config.setConfigJson("{}");
        config.setEnabled(true);
        config.setVersion(0L);
        records.configure(config, 0);
        for (String repository : java.util.List.of("old-a", "old-b")) {
            String workspace = UUID.randomUUID().toString();
            jdbc.update(
                    "insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,git_ref) values(?,?,?,'READY','refs/heads/feature/shared')",
                    workspace,
                    session,
                    repository);
            jdbc.update(
                    "insert into git_session_workspace(conversation_session_id,workspace_id) values(?,?)",
                    session,
                    workspace);
            jdbc.update(
                    "insert into git_change(change_id,workspace_id,conversation_session_id,repository_id,status,commit_id) values(?,?,?,?,'PUSHED',?)",
                    UUID.randomUUID().toString(),
                    workspace,
                    session,
                    repository,
                    "a".repeat(40));
        }
        var mapping = java.util.Map.of("old-a", "repository-a", "old-b", "repository-b");
        assertThat(records.migrateLegacy(project, mapping))
                .allSatisfy(
                        report -> assertThat(report.status()).isEqualTo("MULTIPLE_REPOSITORIES"));
        assertThat(records.project(project).getEnabled()).isFalse();
        assertThat(records.session(session)).isNull();
        assertThat(records.migrateLegacy(project, mapping)).hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from git_change where conversation_session_id=?",
                                Integer.class,
                                session))
                .isEqualTo(2);
    }

    @Test
    void projectWithBindingsCannotChangeRepositoryOrLoseHistory() {
        String projectId = UUID.randomUUID().toString();
        var project = new DevelopmentProjectEntity();
        project.setProjectId(projectId);
        project.setRepository("https://git.example.com/a.git");
        project.setConfigJson("{}");
        project.setVersion(0L);
        project.setEnabled(true);
        records.configure(project, 0L);
        var session = new DevelopmentSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setProjectId(projectId);
        session.setRepository(project.getRepository());
        session.setBranchName("feature/revenue");
        session.setBaselineSha("a".repeat(40));
        session.setConfigSnapshot("{}");
        session.setStorageKey(UUID.randomUUID().toString());
        session.setRevision(0L);
        session.setVersion(0L);
        session.setLocalState("UNINITIALIZED");
        records.bind(session);
        var current = records.project(projectId);
        current.setRepository("https://git.example.com/b.git");
        assertThatThrownBy(() -> records.configure(current, current.getVersion()))
                .isInstanceOf(IllegalStateException.class);
        records.disable(projectId);
        assertThat(records.session(session.getSessionId()).getRepository())
                .isEqualTo(project.getRepository());
        assertThat(records.project(projectId).getEnabled()).isFalse();
        assertThatThrownBy(() -> records.bind(session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameConfigurationVersionCannotBeOverwritten() {
        var project = new DevelopmentProjectEntity();
        project.setProjectId(UUID.randomUUID().toString());
        project.setRepository("https://git.example.com/a.git");
        project.setConfigJson("{}");
        project.setVersion(0L);
        project.setEnabled(true);
        records.configure(project, 0L);
        assertThat(records.project(project.getProjectId()).getVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> records.configure(project, 0L))
                .isInstanceOf(IllegalStateException.class);
    }
}
