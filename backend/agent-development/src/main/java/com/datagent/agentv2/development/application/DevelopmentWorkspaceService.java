package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.OperationType;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.git.application.GitWorkspaceLocks;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity;
import com.datagent.agentv2.persistence.service.development.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 每会话独立 clone，仅持久修订和摘要；文件正文在 Pod 丢失后不可恢复。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class DevelopmentWorkspaceService {
    private static final String UNINITIALIZED = "UNINITIALIZED",
            READY = "READY",
            DIRTY = "DIRTY",
            MUTATING = "MUTATING";
    private static final String GIT = ".git", HEADS = "refs/heads/", EMPTY = "GIT_EMPTY_CHANGE";
    private static final String CONFLICT = "DEVELOPMENT_CONFLICT", LOST = "LOCAL_INPUT_LOST";
    private final DevelopmentContextService contexts;
    private final DevelopmentContextPersistenceService records;
    private final DevelopmentOperationPersistenceService operations;
    private final GitProperties properties;
    private final GitRepositoryClient git;
    private final GitWorkspaceFiles files;
    private final GitWorkspaceLocks locks;

    public <T> T locked(String sessionId, Supplier<T> action) {
        var context = contexts.get(sessionId);
        return locks.workspace(context.repository(), sessionId, action);
    }

    public Path path(DevelopmentSessionEntity row) {
        // storageKey 仅由服务端 UUID 产生，二次检查使损坏记录也无法访问根目录外。
        UUID.fromString(row.getStorageKey());
        return properties.workspaceRoot().resolve(row.getStorageKey());
    }

    public DevelopmentSessionEntity ready(String sessionId) {
        var row = records.session(sessionId);
        if (row == null) throw new DevelopmentException(CONFLICT, "会话没有开发目标", 409);
        Path path = path(row);
        if (!Files.isDirectory(path.resolve(GIT))) {
            if (!UNINITIALIZED.equals(row.getLocalState()) && !READY.equals(row.getLocalState())) {
                throw new DevelopmentException(LOST, "未推送文件已丢失，不能报告保存成功", 409);
            }
            // 曾经保存的内容按已核实 SHA 恢复；不会用聊天记录重新生成草稿。
            if (Files.exists(path)) throw new DevelopmentException(LOST, "本地 clone 不完整", 409);
            git.forRepository(row.getRepository())
                    .cloneRepository(
                            path,
                            HEADS + row.getBranchName(),
                            row.getPushedSha() == null ? row.getBaselineSha() : row.getPushedSha());
            row.setLocalState(READY);
            row.setLocalDigest(fingerprint(row));
            persist(row);
            row = records.session(sessionId);
        }
        if (MUTATING.equals(row.getLocalState())
                || !Objects.equals(row.getLocalDigest(), fingerprint(row))) {
            throw new DevelopmentException(LOST, "本地文件发生未完成或未登记的变化", 409);
        }
        return row;
    }

    public List<String> list(String session, String directory) {
        return locked(session, () -> files.list(path(ready(session)), directory));
    }

    /** 编辑修订来自持久记录；读取不触发动作推进。 */
    public long getRevision(String session) {
        return records.session(session).getRevision();
    }

    public GitSnapshot diff(String session) {
        return locked(
                session,
                () -> {
                    var row = ready(session);
                    var client = git.forRepository(row.getRepository());
                    try {
                        return client.freeze(path(row));
                    } catch (GitException e) {
                        if (EMPTY.equals(e.code()))
                            return new GitSnapshot(client.head(path(row)), "", "", List.of());
                        throw e;
                    }
                });
    }

    /** 只检查本地存在性与登记摘要，不重建工作目录或修改会话。 */
    public String inspect(String session) {
        return locked(
                session,
                () -> {
                    var row = records.session(session);
                    if (!Files.isDirectory(path(row).resolve(GIT))) {
                        return DIRTY.equals(row.getLocalState())
                                        || MUTATING.equals(row.getLocalState())
                                ? LOST
                                : row.getLocalState();
                    }
                    return MUTATING.equals(row.getLocalState())
                                    || !Objects.equals(row.getLocalDigest(), fingerprint(row))
                            ? LOST
                            : row.getLocalState();
                });
    }

    public FileReadResult read(String session, String file) {
        return locked(session, () -> files.read(path(ready(session)), file));
    }

    public List<FileSearchMatch> search(String session, String directory, String query) {
        return locked(session, () -> files.search(path(ready(session)), directory, query));
    }

    public long apply(String session, long revision, List<FileOperation> changes) {
        return locked(
                session,
                () -> {
                    contexts.forNewOperation(session);
                    // 预览核实push后只使用固定SHA；下一批编辑不再影响它，保存和合并仍保护本地输入。
                    if (operations.forSession(session).stream()
                            .anyMatch(
                                    operation ->
                                            DevelopmentOperations.active(operation)
                                                    && !(OperationType.PREVIEW
                                                                    .name()
                                                                    .equals(operation.getType())
                                                            && operation.getPushedSha() != null))) {
                        throw new DevelopmentException(CONFLICT, "开发操作尚未结束，暂不能修改本会话文件", 409);
                    }
                    var row = ready(session);
                    checkRevision(row, revision);
                    String previousState = row.getLocalState();
                    row.setLocalState(MUTATING);
                    persist(row);
                    // 先落屏障再改磁盘；崩溃时拒绝把半次修改当成已登记的完整输入。
                    try {
                        files.apply(path(row), changes);
                    } catch (GitException failure) {
                        restoreUnchangedEdit(row, previousState, failure);
                        throw failure;
                    }
                    row = records.session(session);
                    row.setLocalState(DIRTY);
                    row.setRevision(revision + 1);
                    row.setLocalDigest(fingerprint(row));
                    persist(row);
                    return row.getRevision();
                });
    }

    /** 只有完整文件树与原修订均未变化才能撤销屏障；部分写入或核对失败仍保留MUTATING。 */
    private void restoreUnchangedEdit(
            DevelopmentSessionEntity before, String previousState, GitException failure) {
        try {
            var current = records.session(before.getSessionId());
            if (current != null
                    && MUTATING.equals(current.getLocalState())
                    && Objects.equals(before.getStorageKey(), current.getStorageKey())
                    && Objects.equals(before.getRevision(), current.getRevision())
                    && Objects.equals(before.getLocalDigest(), current.getLocalDigest())
                    && Objects.equals(before.getLocalDigest(), fingerprint(current))) {
                // 重新读取保留并行分析选择等元数据，CAS不覆盖其他更新。
                current.setLocalState(previousState);
                persist(current);
            }
        } catch (RuntimeException verificationFailure) {
            failure.addSuppressed(verificationFailure);
        }
    }

    public void checkRevision(DevelopmentSessionEntity row, long expected) {
        if (row.getRevision() != expected)
            throw new DevelopmentException(CONFLICT, "本地修订已经变化", 409);
    }

    public String fingerprint(DevelopmentSessionEntity row) {
        var client = git.forRepository(row.getRepository());
        try {
            return client.freeze(path(row)).treeId();
        } catch (GitException e) {
            if (EMPTY.equals(e.code())) return client.head(path(row));
            throw e;
        }
    }

    public void persist(DevelopmentSessionEntity row) {
        if (!records.replaceSession(row))
            throw new DevelopmentException(CONFLICT, "会话开发状态已变化", 409);
    }

    public void saved(DevelopmentSessionEntity row, String sha) {
        // 在新目录物化整合后的完整工程，再 CAS 切换；原草稿目录保留，失败不销毁用户输入。
        row.setStorageKey(UUID.randomUUID().toString());
        git.forRepository(row.getRepository())
                .cloneRepository(path(row), HEADS + row.getBranchName(), sha);
        row.setBaselineSha(sha);
        row.setPushedSha(sha);
        row.setLocalState(READY);
        row.setLocalDigest(fingerprint(row));
        persist(row);
    }
}
