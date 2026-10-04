package com.datagent.agentv2.dbt.model;

import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.DbtContracts.DraftChange;
import com.datagent.agentv2.git.model.GitDraftSnapshot;
import com.datagent.agentv2.git.model.GitException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Git 完整差异到受管 SQL/YAML 的纯转换；项目外文件不改变原 Git 规则。 */
public record DbtDraftSnapshot(
        GitDraftSnapshot git, String snapshotKey, List<DraftChange> changes, String changesDigest) {
    private static final String ROOT = ".",
            MODELS = "models/",
            TESTS = "tests/",
            SQL = ".sql",
            YML = ".yml",
            YAML = ".yaml",
            SLASH = "/",
            DOT_DOT = "..",
            GIT = ".git",
            ADD = "A",
            MODIFY = "M",
            DELETE = "D";

    public DbtDraftSnapshot {
        changes = List.copyOf(changes);
    }

    /** 远端配置及工具链是验证身份的一部分；旧无分支键保持不变。 */
    public String validationKey(String configVersion, String toolchainVersion) {
        return git.branchId() == null
                ? snapshotKey
                : DbtChangesDigest.sha256(
                        snapshotKey + "\0" + configVersion + "\0" + toolchainVersion);
    }

    public static DbtDraftSnapshot composeFromGit(GitDraftSnapshot git, DbtProperties properties) {
        String prefix =
                properties.projectDirectory().equals(ROOT)
                        ? ""
                        : properties.projectDirectory() + "/";
        var changes = new ArrayList<DraftChange>();
        var paths = new HashSet<String>();
        boolean branch = git.branchId() != null;
        if (branch && !properties.projectId().equals(git.projectId())) {
            throw new GitException("DBT_MAINTENANCE_SCOPE", "工作区项目与服务配置不匹配");
        }
        long total = 0;
        for (var file : git.files()) {
            if (!prefix.isEmpty() && !file.path().startsWith(prefix)) continue;
            String path = file.path().substring(prefix.length());
            boolean yaml = path.endsWith(YML) || path.endsWith(YAML);
            boolean allowed =
                    branch
                            ? (path.startsWith(MODELS) || path.startsWith(TESTS))
                                    && (yaml || path.endsWith(SQL))
                            : path.startsWith(MODELS) && yaml;
            if (!allowed
                    || path.contains(":")
                    || path.contains("\\")
                    || Arrays.stream(path.split(SLASH, -1))
                            .anyMatch(
                                    segment ->
                                            segment.isEmpty()
                                                    || segment.equals(ROOT)
                                                    || segment.equals(DOT_DOT)
                                                    || segment.equalsIgnoreCase(GIT))
                    || !paths.add(path.toLowerCase(Locale.ROOT))) {
                throw new GitException(
                        "DBT_MAINTENANCE_SCOPE",
                        branch
                                ? "分支仅允许 models/tests 下的 SQL/YAML，路径必须唯一且不越界"
                                : "受管项目仅允许 models 下的 YAML 变更");
            }
            String operation =
                    switch (file.status()) {
                        case ADD -> "CREATE";
                        case MODIFY -> "UPDATE";
                        case DELETE -> "DELETE";
                        default -> throw new GitException("DBT_MAINTENANCE_SCOPE", "只支持文件新增、修改和删除");
                    };
            if (file.newContent() != null) {
                int bytes = file.newContent().getBytes(StandardCharsets.UTF_8).length;
                total += bytes;
                if (bytes > 512 * 1024)
                    throw new GitException("DBT_INPUT_TOO_LARGE", "单文件超过服务校验上限");
                if (yaml) {
                    var options = new LoaderOptions();
                    options.setAllowDuplicateKeys(false);
                    options.setCodePointLimit(512 * 1024);
                    try {
                        new Yaml(new SafeConstructor(options)).load(file.newContent());
                    } catch (RuntimeException invalid) {
                        throw new GitException("DBT_INVALID_YAML", "YAML 语法错误或存在重复键");
                    }
                }
            }
            changes.add(
                    new DraftChange(
                            path,
                            operation,
                            file.oldContent() == null
                                    ? null
                                    : DbtChangesDigest.sha256(file.oldContent()),
                            file.newContent()));
        }
        if (changes.size() > 100 || total > 5 * 1024 * 1024)
            throw new GitException("DBT_INPUT_TOO_LARGE", "验证操作总量超过上限");
        String identity =
                String.join(
                        "\0",
                        git.sessionId(),
                        git.workspaceId(),
                        properties.projectId(),
                        properties.projectDirectory(),
                        git.baseCommitSha(),
                        git.treeId(),
                        Long.toString(git.draftRevision()));
        if (branch) identity += "\0" + git.branchId() + "\0" + git.gitRef();
        return new DbtDraftSnapshot(
                git,
                DbtChangesDigest.sha256(identity),
                changes,
                branch ? DbtChangesDigest.digestV2(changes) : DbtChangesDigest.digest(changes));
    }
}
