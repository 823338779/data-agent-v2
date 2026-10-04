package com.datagent.agentv2.git.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitBranchRegistry;
import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;
import com.datagent.agentv2.persistence.service.git.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 使用独立真实 clone 验证分支草稿，不依赖会话到单工作区的旧约束。 */
class GitBranchWorkspaceTest {
    @TempDir Path temp;
    final Map<String, GitWorkspaceEntity> stored = new HashMap<>();
    final GitWorkspacePersistenceService records = mock(GitWorkspacePersistenceService.class);
    final GitSessionWorkspaceService sessions = mock(GitSessionWorkspaceService.class);
    final GitBranchRegistry registry = mock(GitBranchRegistry.class);
    GitWorkspaceService workspaces;
    String base;

    @BeforeEach
    void setup() throws Exception {
        Path remote = temp.resolve("remote.git");
        run(temp, "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        run(temp, "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("metrics.yml"), "original\n");
        run(seed, "add", ".");
        run(
                seed,
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@example.test",
                "commit",
                "-m",
                "seed");
        base = run(seed, "rev-parse", "HEAD");
        run(seed, "push", "origin", "HEAD:main", "HEAD:dev/a", "HEAD:dev/b");
        var properties =
                new GitProperties(
                        "git",
                        remote.toString(),
                        temp.resolve("work"),
                        Duration.ofSeconds(15),
                        4194304,
                        524288,
                        20,
                        100,
                        5242880,
                        2097152);
        when(records.findByWorkspaceId(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(records.findCreationRequest(anyString(), anyString()))
                .thenAnswer(
                        i ->
                                stored.values().stream()
                                        .filter(
                                                w ->
                                                        w.getConversationSessionId()
                                                                        .equals(i.getArgument(0))
                                                                && w.getCreationKey()
                                                                        .equals(i.getArgument(1)))
                                        .findFirst()
                                        .orElse(null));
        when(records.save(any()))
                .thenAnswer(
                        i -> {
                            GitWorkspaceEntity w = i.getArgument(0);
                            stored.put(w.getWorkspaceId(), w);
                            return true;
                        });
        when(records.replace(any())).thenReturn(true);
        for (String branch : List.of("a", "b")) {
            when(registry.get(branch))
                    .thenReturn(
                            JsonMapper.builder()
                                    .build()
                                    .createObjectNode()
                                    .put("branchId", branch)
                                    .put("projectId", "metrics")
                                    .put("gitRef", "refs/heads/dev/" + branch)
                                    .put("mode", "PREVIEW")
                                    .put("status", "ACTIVE"));
        }
        workspaces =
                new GitWorkspaceService(
                        records,
                        mock(GitChangePersistenceService.class),
                        new GitRepositoryClient(properties),
                        new GitWorkspaceFiles(properties),
                        properties,
                        new GitApplicationProperties("metrics", "Agent", "agent@example.test"),
                        new GitWorkspaceLocks(),
                        registry,
                        sessions);
    }

    @Test
    void independentClonesAndIdempotentCreationDoNotOverwriteDrafts() {
        var a = workspaces.create("session", "a", base, "key-a");
        var b = workspaces.create("session", "b", base, "key-b");
        var another = workspaces.create("other-session", "a", base, "key-a");
        assertNotEquals(a.workspaceId(), b.workspaceId());
        assertNotEquals(a.workspaceId(), another.workspaceId());
        workspaces.apply(
                a.workspaceId(),
                List.of(
                        FileOperation.update(
                                "metrics.yml",
                                workspaces.read(a.workspaceId(), "metrics.yml").sha256(),
                                "changed\n")),
                a.draftRevision());
        assertEquals(
                a.workspaceId(), workspaces.create("session", "a", base, "key-a").workspaceId());
        assertEquals("changed\n", workspaces.read(a.workspaceId(), "metrics.yml").content());
        assertEquals("original\n", workspaces.read(b.workspaceId(), "metrics.yml").content());
        assertEquals("original\n", workspaces.read(another.workspaceId(), "metrics.yml").content());
        assertEquals(
                "GIT_CONFLICT",
                assertThrows(
                                GitException.class,
                                () -> workspaces.create("session", "b", base, "key-a"))
                        .code());
        assertThrows(GitException.class, () -> workspaces.prepare("session", false));
        verify(sessions, times(2)).attach("session", a.workspaceId());
    }

    @Test
    void rejectsProductionAndUnsafeBaseBeforeCreatingWorkspace() {
        when(registry.get("main"))
                .thenReturn(
                        JsonMapper.builder()
                                .build()
                                .createObjectNode()
                                .put("branchId", "main")
                                .put("projectId", "metrics")
                                .put("gitRef", "refs/heads/main")
                                .put("mode", "PRODUCTION")
                                .put("status", "ACTIVE"));
        assertThrows(GitException.class, () -> workspaces.create("session", "main", base, "key"));
        assertThrows(
                GitException.class,
                () -> workspaces.create("session", "a", "--upload-pack=bad", "key"));
        assertTrue(stored.isEmpty());
    }

    private static String run(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        var process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }
}
