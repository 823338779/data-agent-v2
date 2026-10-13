package com.datagent.agentv2.development;

import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.git.application.GitWorkspaceLocks;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.FileOperation;
import com.datagent.agentv2.persistence.*;
import com.datagent.agentv2.persistence.entity.development.*;
import com.datagent.agentv2.persistence.service.development.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * 保存、预览与交付测试共用的真实 Git/数据库准备逻辑。
 *
 * <p>此基类不声明测试方法，避免 JUnit 在子类中重复执行父类场景；每个场景仍创建独立仓库和项目。
 */
@SpringBootTest(
        classes = AbstractDevelopmentIntegrationSupport.Config.class,
        properties = "data-agent.context.strategy=none")
abstract class AbstractDevelopmentIntegrationSupport {
    static final String PREVIEW_SCHEMA = "preview_metrics",
            PRODUCTION_SCHEMA = "production_metrics";
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Configuration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        AuditMetaObjectHandler.class,
        DevelopmentContextPersistenceService.class,
        DevelopmentOperationPersistenceService.class
    })
    static class Config {}

    @Autowired DevelopmentContextPersistenceService contexts;
    @Autowired DevelopmentOperationPersistenceService records;
    @Autowired ObjectMapper json;
    @TempDir Path root;
    DevelopmentContextService context;
    DevelopmentOperations operations;
    DevelopmentWorkspaceService files;
    SourceSaveService saves;
    GitRepositoryClient git;
    Path seed;
    String repository;
    String base;
    String project;

    @BeforeEach
    void setup() throws Exception {
        Path remote = root.resolve("remote.git");
        repository = remote.toString();
        run(root, "init", "--bare", "--initial-branch=main", repository);
        seed = root.resolve("seed");
        run(root, "clone", repository, seed.toString());
        Files.writeString(seed.resolve("dbt_project.yml"), "name: test\n");
        run(seed, "add", ".");
        run(
                seed,
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@example.test",
                "commit",
                "-m",
                "base");
        run(seed, "push", "origin", "main");
        base = run(seed, "rev-parse", "HEAD");
        var properties =
                new GitProperties(
                        "git",
                        repository,
                        root.resolve("work"),
                        Duration.ofSeconds(20),
                        2_000_000,
                        1_000_000,
                        100,
                        100,
                        2_000_000,
                        2_000_000);
        git = new GitRepositoryClient(properties);
        git.createBranch(repository, "feature/shared", base);
        project = UUID.randomUUID().toString();
        var config =
                new DevelopmentConfig(
                        repository,
                        ExecutionFixtures.config().previewExecution(),
                        ExecutionFixtures.config().productionExecution(),
                        1);
        var row = new DevelopmentProjectEntity();
        row.setProjectId(project);
        row.setRepository(repository);
        row.setConfigJson(json.writeValueAsString(config));
        row.setEnabled(true);
        row.setVersion(0L);
        contexts.configure(row, 0);
        context = mock(DevelopmentContextService.class);
        operations = new DevelopmentOperations(context, records, json);
        files =
                new DevelopmentWorkspaceService(
                        context,
                        contexts,
                        records,
                        properties,
                        git,
                        new GitWorkspaceFiles(properties),
                        new GitWorkspaceLocks());
        saves = new SourceSaveService(operations, files, contexts, git);
    }

    String session() {
        String id = UUID.randomUUID().toString();
        var config =
                new DevelopmentConfig(
                        repository,
                        ExecutionFixtures.config().previewExecution(),
                        ExecutionFixtures.config().productionExecution(),
                        1);
        var c = new DevelopmentContext(id, project, repository, "feature/shared", base, config);
        when(context.get(id)).thenReturn(c);
        when(context.forNewOperation(id)).thenReturn(c);
        var s = new DevelopmentSessionEntity();
        s.setSessionId(id);
        s.setProjectId(project);
        s.setRepository(repository);
        s.setBranchName("feature/shared");
        s.setBaselineSha(base);
        s.setConfigSnapshot(json.writeValueAsString(config));
        s.setStorageKey(UUID.randomUUID().toString());
        s.setRevision(0L);
        s.setVersion(0L);
        s.setLocalState("UNINITIALIZED");
        contexts.bind(s);
        files.list(id, ".");
        return id;
    }

    void edit(String session, String path, String content) {
        files.apply(
                session,
                0,
                List.of(new FileOperation(FileOperation.Type.CREATE, path, null, content)));
    }

    // 构建响应仅作为引擎替身，预览和交付复用同一契约构造。
    BuildView buildView(UUID id, BuildRequest request, String status, String deployment) {
        var body = json.valueToTree(request).deepCopy();
        var node = (tools.jackson.databind.node.ObjectNode) body;
        node.remove("deploymentPolicy");
        node.remove("idempotencyKey");
        node.remove("origin");
        node.put("buildId", id.toString());
        node.put("requestedCommitSha", request.commitSha());
        node.put("toolchainVersion", "test");
        node.put("buildStatus", status);
        node.put("phase", "SEALED");
        node.put("createdAt", "2026-10-10T00:00:00Z");
        node.put("updatedAt", "2026-10-10T00:00:00Z");
        node.put("catalogAvailable", true);
        node.put("queryAvailable", true);
        node.put("cancelRequested", false);
        var attempt = node.putObject("initialDeployment");
        attempt.put("repository", request.repository());
        attempt.put("branchName", request.branchName());
        attempt.put("environmentId", request.execution().environmentId().toString());
        attempt.put("generation", 1);
        attempt.put("buildId", id.toString());
        attempt.put("deploymentStatus", deployment);
        attempt.put("createdAt", "2026-10-10T00:00:00Z");
        try {
            return json.treeToValue(node, BuildView.class);
        } catch (Exception malformedFixture) {
            throw new AssertionError("Invalid build fixture", malformedFixture);
        }
    }

    static String run(Path path, String... arguments) {
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.addAll(List.of(arguments));
            var p =
                    new ProcessBuilder(cmd)
                            .directory(path.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(
                            p.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IllegalStateException(output);
            return output.trim();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
