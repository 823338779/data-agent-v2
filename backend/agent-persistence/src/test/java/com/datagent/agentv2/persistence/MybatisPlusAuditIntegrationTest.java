package com.datagent.agentv2.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({
    AuditFixtureService.class,
    com.datagent.agentv2.persistence.service.git.GitWorkspacePersistenceService.class,
    MybatisPlusAuditIntegrationTest.FixtureMapperConfiguration.class
})
class MybatisPlusAuditIntegrationTest {

    /** 业务和数据库会话统一使用的北京时间时区。 */
    private static final String BUSINESS_TIME_ZONE_NAME = "Asia/Shanghai";

    /** 查询当前 PostgreSQL 会话时区。 */
    private static final String SHOW_TIME_ZONE_SQL = "show time zone";

    /** 测试时间边界使用的北京时间时区。 */
    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of(BUSINESS_TIME_ZONE_NAME);

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Autowired private AuditFixtureService auditFixtureService;
    @Autowired private JdbcTemplate jdbc;

    @Autowired
    private com.datagent.agentv2.persistence.service.git.GitWorkspacePersistenceService workspaces;

    @BeforeEach
    void seedTenants() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (tenant_id, name) values ('tenant_local', 'Local')");
        jdbc.update("insert into tenant (tenant_id, name) values ('tenant_two', 'Two')");
    }

    @Test
    void saveAndUpdateFillAuditAndTenantFields() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    LocalDateTime beforeSave = LocalDateTime.now(BUSINESS_TIME_ZONE);
                    AuditFixtureEntity entity =
                            AuditFixtureEntity.session("session_audit", "Audit");
                    assertThat(auditFixtureService.save(entity)).isTrue();
                    LocalDateTime afterSave = LocalDateTime.now(BUSINESS_TIME_ZONE);
                    assertThat(entity.getCreatedAt()).isBetween(beforeSave, afterSave);
                    assertThat(entity.getUpdatedAt()).isBetween(beforeSave, afterSave);
                    assertThat(entity.isDeleted()).isFalse();
                    assertThat(entity.getDeletedAt()).isNull();
                    assertThat(entity.getTenantId()).isEqualTo("tenant_local");

                    // 重新读取以验证 PostgreSQL 本地时间字段可以映射为 LocalDateTime。
                    AuditFixtureEntity reloaded =
                            auditFixtureService.findByConversationSessionId("session_audit");
                    assertThat(reloaded).isNotNull();
                    assertThat(reloaded.getCreatedAt()).isBetween(beforeSave, afterSave);
                    assertThat(reloaded.getUpdatedAt()).isBetween(beforeSave, afterSave);
                });
    }

    @Test
    void workspaceCasRefreshesAuditTimeWithoutChangingCreatedAt() {
        var old = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update(
                "insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,created_at,updated_at) values (?,?,?,?,?,?)",
                "audit_workspace",
                "session",
                "repo",
                "READY",
                old,
                old);
        var workspace = workspaces.findByWorkspaceId("audit_workspace");
        workspace.setStatus("MUTATING");
        assertThat(workspaces.replace(workspace)).isTrue();
        var reloaded = workspaces.findByWorkspaceId("audit_workspace");
        assertThat(reloaded.getUpdatedAt()).isAfter(old);
        assertThat(reloaded.getCreatedAt()).isEqualTo(old);
    }

    @Test
    void updatingReloadedEntityRefreshesItsExistingAuditTimestamp() {
        var old = LocalDateTime.of(2000, 1, 1, 0, 0);
        var entity = AuditFixtureEntity.session("session_update_audit", "Before");
        auditFixtureService.save(entity);
        jdbc.update(
                "update conversation_session set updated_at = ? where conversation_session_id = ?",
                old,
                "session_update_audit");
        var loaded = auditFixtureService.findByConversationSessionId("session_update_audit");
        loaded.setTitle("After");
        assertThat(auditFixtureService.updateById(loaded)).isTrue();
        assertThat(
                        auditFixtureService
                                .findByConversationSessionId("session_update_audit")
                                .getUpdatedAt())
                .isAfter(old);
    }

    @Test
    void newDatabaseConnectionsUseBeijingTimeZone() throws SQLException {
        // 绕过应用连接池新建直连，验证数据库级默认时区同样对直接查询生效。
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(SHOW_TIME_ZONE_SQL)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(BUSINESS_TIME_ZONE_NAME);
        }
    }

    @Test
    void tenantInterceptorHidesAnotherTenant() {
        TenantContext.run(
                "tenant_local",
                () -> auditFixtureService.save(AuditFixtureEntity.session("session_one", "One")));

        TenantContext.run(
                "tenant_two",
                () ->
                        assertThat(auditFixtureService.findByConversationSessionId("session_one"))
                                .isNull());
    }

    @Test
    void removeByIdFillsLogicalDeleteAuditFieldsWithoutCustomServiceBaseClass() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    AuditFixtureEntity entity =
                            AuditFixtureEntity.session("session_deleted", "Delete");
                    auditFixtureService.save(entity);
                    assertThat(auditFixtureService.removeById(entity.getId())).isTrue();
                });

        var deleted =
                jdbc.queryForMap(
                        "select is_deleted, deleted_at from conversation_session where conversation_session_id = ?",
                        "session_deleted");
        assertThat(deleted.get("is_deleted")).isEqualTo(true);
        assertThat(deleted.get("deleted_at")).isNotNull();
    }

    @Test
    void updateByIdRejectsAStaleVersion() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    AuditFixtureEntity current =
                            AuditFixtureEntity.session("session_version", "Initial");
                    auditFixtureService.save(current);

                    AuditFixtureEntity stale =
                            AuditFixtureEntity.session("session_version", "Stale");
                    stale.setId(current.getId());
                    stale.setVersion(current.getVersion());
                    current.setTitle("Current");

                    assertThat(auditFixtureService.updateById(current)).isTrue();
                    assertThat(current.getVersion()).isEqualTo(1);
                    assertThat(auditFixtureService.updateById(stale)).isFalse();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(
            basePackageClasses = AuditFixtureMapper.class,
            markerInterface = AuditFixtureMapperMarker.class)
    static class FixtureMapperConfiguration {}
}
