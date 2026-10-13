package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.git.model.FileOperation;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** 真 Git 与真数据库验证：保存不依赖 dbt，重试只恢复原候选。 */
class SourceSaveIntegrationTest extends AbstractDevelopmentIntegrationSupport {
    @Test
    void twoSessionsReconcileWithoutLosingEitherChange() {
        String a = session(), b = session();
        edit(a, "macros/a.sql", "select 1");
        edit(b, "seeds/b.csv", "id\n1");
        var first = saves.start(a, new SaveRequest("save", 1, "save A"));
        var second = saves.start(b, new SaveRequest("save", 1, "save B"));
        assertThat(saves.reconcile(first.operationId()).status())
                .isEqualTo(OperationStatus.SUCCEEDED);
        var result = saves.reconcile(second.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(files.list(b, ".")).contains("macros/a.sql", "seeds/b.csv", "dbt_project.yml");
        assertThat(git.verifyPush(repository, "feature/shared", result.pushedSha()).verified())
                .isTrue();
        assertThat(saves.start(b, new SaveRequest("save", 1, "save B")).operationId())
                .isEqualTo(result.operationId());
    }

    @Test
    void invalidYamlCanBeSaved() {
        String s = session();
        edit(s, "models/invalid.yml", "invalid: [");
        var accepted = saves.start(s, new SaveRequest("save", 1, "work in progress"));
        assertThat(saves.reconcile(accepted.operationId()).status())
                .isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void rejectedFileOperationKeepsExistingDraftEditable() {
        String s = session();
        edit(s, DRAFT_PATH, DRAFT_CONTENT);
        var rejected =
                new FileOperation(
                        FileOperation.Type.UPDATE, DRAFT_PATH, STALE_DIGEST, UPDATED_CONTENT);
        // 普通摘要冲突没有写盘，不能把仍完整的用户草稿标为丢失。
        assertThatThrownBy(() -> files.apply(s, 1, List.of(rejected)))
                .isInstanceOf(com.datagent.agentv2.git.model.GitException.class);
        assertThat(contexts.session(s).getLocalState()).isEqualTo(DIRTY_STATE);
        assertThat(contexts.session(s).getRevision()).isEqualTo(1);
        var current = files.read(s, DRAFT_PATH);
        assertThat(current.content()).isEqualTo(DRAFT_CONTENT);
        assertThat(
                        files.apply(
                                s,
                                1,
                                List.of(
                                        new FileOperation(
                                                FileOperation.Type.UPDATE,
                                                DRAFT_PATH,
                                                current.sha256(),
                                                UPDATED_CONTENT))))
                .isEqualTo(2);
        assertThat(files.read(s, DRAFT_PATH).content()).isEqualTo(UPDATED_CONTENT);
    }

    @Test
    void rejectedEmptyEditKeepsCleanWorkspaceUsable() {
        String s = session();
        assertThatThrownBy(() -> files.apply(s, 0, List.of()))
                .isInstanceOf(com.datagent.agentv2.git.model.GitException.class);
        assertThat(contexts.session(s).getLocalState()).isEqualTo(READY_STATE);
        edit(s, DRAFT_PATH, DRAFT_CONTENT);
        assertThat(files.read(s, DRAFT_PATH).content()).isEqualTo(DRAFT_CONTENT);
    }

    @Test
    void saveStillProtectsItsUnpushedInputFromEditing() {
        String s = session();
        edit(s, DRAFT_PATH, DRAFT_CONTENT);
        saves.start(s, new SaveRequest(SAVE_KEY, 1, SAVE_MESSAGE));
        var current = files.read(s, DRAFT_PATH);
        assertThatThrownBy(
                        () ->
                                files.apply(
                                        s,
                                        1,
                                        List.of(
                                                new FileOperation(
                                                        FileOperation.Type.UPDATE,
                                                        DRAFT_PATH,
                                                        current.sha256(),
                                                        UPDATED_CONTENT))))
                .isInstanceOf(com.datagent.agentv2.development.model.DevelopmentException.class);
        assertThat(files.read(s, DRAFT_PATH).content()).isEqualTo(DRAFT_CONTENT);
    }

    @Test
    void partiallyWrittenEditRemainsBlocked() throws Exception {
        String s = session();
        edit(s, DRAFT_PATH, DRAFT_CONTENT);
        var current = files.read(s, DRAFT_PATH);
        // 第二项的父路径是普通文件：第一项写入后才发生I/O失败，不能恢复为完整旧修订。
        assertThatThrownBy(
                        () ->
                                files.apply(
                                        s,
                                        1,
                                        List.of(
                                                new FileOperation(
                                                        FileOperation.Type.UPDATE,
                                                        DRAFT_PATH,
                                                        current.sha256(),
                                                        UPDATED_CONTENT),
                                                new FileOperation(
                                                        FileOperation.Type.CREATE,
                                                        DRAFT_PATH + CHILD_PATH,
                                                        null,
                                                        DRAFT_CONTENT))))
                .isInstanceOf(com.datagent.agentv2.git.model.GitException.class);
        assertThat(contexts.session(s).getLocalState()).isEqualTo(MUTATING_STATE);
        assertThat(contexts.session(s).getRevision()).isEqualTo(1);
        assertThat(Files.readString(files.path(contexts.session(s)).resolve(DRAFT_PATH)))
                .isEqualTo(UPDATED_CONTENT);
        assertThatThrownBy(() -> files.ready(s))
                .isInstanceOf(com.datagent.agentv2.development.model.DevelopmentException.class);
    }

    private static final String DRAFT_PATH = "macros/draft.sql",
            DRAFT_CONTENT = "select 1",
            UPDATED_CONTENT = "select 2",
            STALE_DIGEST = "stale",
            DIRTY_STATE = "DIRTY",
            READY_STATE = "READY",
            SAVE_KEY = "save",
            SAVE_MESSAGE = "save draft",
            CHILD_PATH = "/child.sql",
            MUTATING_STATE = "MUTATING";

    @Test
    void missingLocalInputDoesNotClaimSaved() throws Exception {
        String s = session();
        edit(s, "macros/lost.sql", "select 1");
        var operation = saves.start(s, new SaveRequest("save", 1, "save"));
        Path directory = root.resolve("work").resolve(contexts.session(s).getStorageKey());
        try (var paths = Files.walk(directory)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.delete(p);
            }
        }
        var result = saves.reconcile(operation.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.BLOCKED);
        assertThat(result.pushedSha()).isNull();
    }
}
