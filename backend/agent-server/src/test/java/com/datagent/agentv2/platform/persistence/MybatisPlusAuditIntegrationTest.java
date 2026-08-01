package com.datagent.agentv2.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import({
    AuditFixtureService.class,
    MybatisPlusAuditIntegrationTest.FixedClockConfiguration.class,
    MybatisPlusAuditIntegrationTest.FixtureMapperConfiguration.class
})
class MybatisPlusAuditIntegrationTest {

    private static final Instant INSTANT = Instant.parse("2026-08-01T08:00:00Z");
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

    @BeforeEach
    void seedTenantsAndUsers() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (id, name) values ('tenant_local', 'Local')");
        jdbc.update("insert into tenant (id, name) values ('tenant_two', 'Two')");
        jdbc.update(
                """
                insert into user_account (id, tenant_id, username, password_hash)
                values ('user_admin', 'tenant_local', 'admin', 'not-used')
                """);
        jdbc.update(
                """
                insert into user_account (id, tenant_id, username, password_hash)
                values ('user_two', 'tenant_two', 'two', 'not-used')
                """);
    }

    @Test
    void saveAndUpdateFillAuditAndTenantFields() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    AuditFixtureEntity entity =
                            AuditFixtureEntity.session("session_audit", "Audit");
                    assertThat(auditFixtureService.save(entity)).isTrue();
                    assertThat(entity.getCreatedAt()).isEqualTo(INSTANT);
                    assertThat(entity.getUpdatedAt()).isEqualTo(INSTANT);
                    assertThat(entity.isDeleted()).isFalse();
                    assertThat(entity.getDeletedAt()).isNull();
                    assertThat(entity.getTenantId()).isEqualTo("tenant_local");
                });
    }

    @Test
    void tenantInterceptorHidesAnotherTenant() {
        TenantContext.run(
                "tenant_local",
                () -> auditFixtureService.save(AuditFixtureEntity.session("session_one", "One")));

        TenantContext.run(
                "tenant_two",
                () -> assertThat(auditFixtureService.getById("session_one")).isNull());
    }

    @Test
    void removeByIdFillsLogicalDeleteAuditFields() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    AuditFixtureEntity entity =
                            AuditFixtureEntity.session("session_deleted", "Delete");
                    auditFixtureService.save(entity);
                    assertThat(auditFixtureService.removeById(entity)).isTrue();
                });

        var deleted =
                jdbc.queryForMap(
                        "select is_deleted, deleted_at from agent_session where id = ?",
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
                    stale.setVersion(current.getVersion());
                    current.setTitle("Current");

                    assertThat(auditFixtureService.updateById(current)).isTrue();
                    assertThat(current.getVersion()).isEqualTo(1);
                    assertThat(auditFixtureService.updateById(stale)).isFalse();
                });
    }

    @Test
    void applicationComponentsOutsidePersistenceDoNotDependOnMappers() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> violations = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents("com.datagent.agentv2")) {
            String className = candidate.getBeanClassName();
            if (className == null || className.contains(".persistence.")) {
                continue;
            }
            Class<?> type = Class.forName(className);
            Arrays.stream(type.getDeclaredFields())
                    .map(Field::getType)
                    .filter(BaseMapper.class::isAssignableFrom)
                    .map(fieldType -> type.getName() + " field " + fieldType.getName())
                    .forEach(violations::add);
            Arrays.stream(type.getDeclaredConstructors())
                    .map(Constructor::getParameterTypes)
                    .flatMap(Arrays::stream)
                    .filter(BaseMapper.class::isAssignableFrom)
                    .map(
                            parameterType ->
                                    type.getName() + " constructor " + parameterType.getName())
                    .forEach(violations::add);
        }

        assertThat(violations).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(INSTANT, ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = AuditFixtureMapper.class)
    static class FixtureMapperConfiguration {}
}
