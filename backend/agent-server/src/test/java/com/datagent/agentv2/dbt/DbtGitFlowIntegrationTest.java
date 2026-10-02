package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.application.*;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.*;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import com.datagent.agentv2.persistence.service.git.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** 真实 Git 和数据库验证持久凭证、编辑失效与确认推送；远端仅为临时裸仓库。 */
@SpringBootTest(
        classes = DbtGitFlowIntegrationTest.Config.class,
        properties = "data-agent.context.strategy=none")
class DbtGitFlowIntegrationTest {
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
        GitWorkspacePersistenceService.class,
        GitChangePersistenceService.class,
        DbtValidationPersistenceService.class
    })
    static class Config {}

    @Autowired GitWorkspacePersistenceService workspaceRecords;
    @Autowired GitChangePersistenceService changeRecords;
    @Autowired DbtValidationPersistenceService validationRecords;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temp;
    final JsonMapper json = new JsonMapper();
    final DbtServiceClient client = mock(DbtServiceClient.class);
    final DbtProperties dbt =
            new DbtProperties(
                    true,
                    URI.create("http://localhost:1"),
                    "sample",
                    ".",
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(20));
    GitWorkspaceService workspaces;
    GitChangeService changes;
    GitSubmissionService submissions;
    DbtValidationService validations;
    ArrayDeque<Runnable> queue = new ArrayDeque<>();
    Path remote;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute(
                "truncate table dbt_validation, git_change, git_workspace restart identity cascade");
        remote = temp.resolve("remote.git");
        git(temp, "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        git(temp, "clone", remote.toString(), seed.toString());
        Files.createDirectory(seed.resolve("models"));
        Files.writeString(seed.resolve("models/metrics.yml"), "version: 2\n");
        git(seed, "add", ".");
        git(
                seed,
                "-c",
                "user.name=fixture",
                "-c",
                "user.email=fixture@test",
                "commit",
                "-m",
                "seed");
        git(seed, "push", "origin", "main");
        var p =
                new GitProperties(
                        "git",
                        remote.toString(),
                        temp.resolve("data"),
                        Duration.ofSeconds(15),
                        4 * 1024 * 1024,
                        512 * 1024,
                        20,
                        100,
                        5 * 1024 * 1024,
                        2 * 1024 * 1024);
        var app = new GitApplicationProperties("metrics", "Agent", "agent@test");
        var repository = new GitRepositoryClient(p);
        workspaces =
                new GitWorkspaceService(
                        workspaceRecords,
                        changeRecords,
                        repository,
                        new GitWorkspaceFiles(p),
                        p,
                        app,
                        new GitWorkspaceLocks());
        changes =
                new GitChangeService(
                        workspaces,
                        changeRecords,
                        new GitSnapshotStore(p),
                        repository,
                        app,
                        new DbtCandidateValidation(validationRecords, dbt, json),
                        (execution, candidate) -> {
                            candidate.setConversationMessageId("message");
                            changeRecords.register(candidate);
                        });
        submissions =
                new GitSubmissionService(
                        workspaces,
                        changes,
                        changeRecords,
                        repository,
                        queue::add,
                        Clock.systemUTC());
        validations = new DbtValidationService(workspaces, validationRecords, client, dbt, json);
        when(client.project())
                .thenReturn(
                        json.readTree(
                                "{\"protocolVersion\":\"agent-dbt-v1\",\"projectSubdir\":\".\",\"capabilities\":[\"draft-validation-v1\"]}"));
        when(client.submitValidation(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var request =
                                    call.getArgument(0, tools.jackson.databind.JsonNode.class);
                            String id = UUID.randomUUID().toString();
                            var row =
                                    validationRecords.list().stream()
                                            .filter(
                                                    item ->
                                                            item.getBaseCommitSha()
                                                                            .equals(
                                                                                    request.path(
                                                                                                    "baseCommitSha")
                                                                                            .asString())
                                                                    && item.getValidationId()
                                                                            == null)
                                            .findFirst()
                                            .orElseThrow();
                            var result =
                                    json.createObjectNode()
                                            .put("validationId", id)
                                            .put("state", "SUCCEEDED")
                                            .put("baseCommitSha", row.getBaseCommitSha())
                                            .put("changesDigest", row.getChangesDigest())
                                            .put("projectSubdir", ".")
                                            .put("configVersion", "config")
                                            .put("toolchainVersion", "tools")
                                            .put("validatedProjectDigest", "a".repeat(64))
                                            .put("completedAt", "2026-10-02T00:00:00Z")
                                            .put("valid", true);
                            result.putArray("checkedLevels")
                                    .add("YAML")
                                    .add("TEMPLATE")
                                    .add("DBT_PARSE")
                                    .add("SEMANTIC");
                            when(client.getValidation(id)).thenReturn(result);
                            return json.createObjectNode()
                                    .put("validationId", id)
                                    .put("state", "QUEUED");
                        });
    }

    @Test
    void evidenceSurvivesRestartButEditsRequireNewValidationAndConfirmation() throws Exception {
        var initial = workspaces.prepare("session", false);
        workspaces.apply(
                "session",
                List.of(
                        FileOperation.update(
                                "models/metrics.yml",
                                workspaces.read("session", "models/metrics.yml").sha256(),
                                "version: 2\n# first\n")));
        long revision = initial.draftRevision() + 1;
        assertThatThrownBy(
                        () ->
                                changes.prepare(
                                        "session", "execution", "before", "修改", "update", revision))
                .isInstanceOf(GitException.class);
        var result = validations.submit("session", revision);
        assertThat(result.path("valid").asBoolean()).isTrue();
        assertThatThrownBy(
                        () ->
                                validations.get(
                                        "other-session", result.path("validationId").asString()))
                .hasMessageContaining("不存在");
        // 新应用服务实例从数据库恢复证据，准备和确认阶段不能再访问 dbt HTTP。
        validations = new DbtValidationService(workspaces, validationRecords, client, dbt, json);
        clearInvocations(client);
        assertThat(validations.get("session", result.path("validationId").asString()))
                .isEqualTo(result);
        var first = changes.prepare("session", "execution", "first", "修改", "update", revision);
        assertThat(first.validation()).isNotNull();
        verifyNoInteractions(client);
        workspaces.apply(
                "session",
                List.of(
                        FileOperation.update(
                                "models/metrics.yml",
                                workspaces.read("session", "models/metrics.yml").sha256(),
                                "version: 2\n# second\n")));
        assertThatThrownBy(() -> submissions.confirm("session", first.changeId(), first.version()))
                .isInstanceOf(GitException.class);
        assertThatThrownBy(
                        () ->
                                changes.prepare(
                                        "session",
                                        "execution",
                                        "stale",
                                        "修改",
                                        "update",
                                        revision + 1))
                .isInstanceOf(GitException.class);
        validations.submit("session", revision + 1);
        clearInvocations(client);
        var second =
                changes.prepare("session", "execution", "second", "修改", "update", revision + 1);
        submissions.confirm("session", second.changeId(), second.version());
        queue.remove().run();
        assertThat(changes.detail("session", second.changeId()).status())
                .isEqualTo(GitChangeStatus.PUSHED);
        assertThat(git(temp, "--git-dir=" + remote, "show", "main:models/metrics.yml"))
                .contains("# second");
        verifyNoInteractions(client);
    }

    private static String git(Path directory, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        var process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
