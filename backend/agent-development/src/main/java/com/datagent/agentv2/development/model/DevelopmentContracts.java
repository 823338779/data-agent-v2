package com.datagent.agentv2.development.model;

import java.util.List;
import java.util.UUID;

/** 开发动作的唯一公开模型；目标来自会话绑定，不接受模型覆盖仓库或分支。 */
public final class DevelopmentContracts {
    private static final String SHA_PATTERN = "[0-9a-f]{40}(?:[0-9a-f]{24})?";
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
    private static final String MAIN = "main", REFS = "refs/", SSH = "ssh", URI_SEPARATOR = "://";
    private static final String NEWLINE = "\n", RETURN = "\r", COLON = ":", DASH = "-";
    private static final String DOTS = "..", REFLOG = "@{", SLASH = "/", DOT = ".";
    private static final String INVALID_BRANCH = ".*[\\s~^:?*\\[\\\\].*";

    private DevelopmentContracts() {}

    public enum OperationType {
        SAVE,
        PREVIEW,
        DELIVER,
        CREATE_BRANCH
    }

    public enum OperationStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        BLOCKED,
        RESULT_UNKNOWN,
        FAILED
    }

    public enum Phase {
        PREPARING,
        RECONCILING_REMOTE,
        PUSHING,
        VERIFYING,
        PUSHED,
        SAVING,
        BUILD_SUBMITTING,
        BUILD_RUNNING,
        DEPLOYMENT_PENDING,
        DEPLOYED,
        PRECHECK,
        MERGING,
        MERGED,
        RELEASE_SUBMITTING,
        RELEASING,
        RELEASED
    }

    /** 每个环境都固定绑定和语义配置版本；version 是 Agent 项目配置的 CAS 版本。 */
    public record DevelopmentConfig(
            /** 受控Git仓库地址，不含凭据。 */
            String repository,
            /** 预览环境执行绑定。 */
            String previewBinding,
            /** 预览语义配置版本。 */
            String previewConfigVersion,
            /** 正式环境执行绑定。 */
            String productionBinding,
            /** 正式语义配置版本。 */
            String productionConfigVersion,
            /** Agent配置的并发更新版本。 */
            long version) {
        public DevelopmentConfig {
            for (String value :
                    new String[] {
                        repository,
                        previewBinding,
                        previewConfigVersion,
                        productionBinding,
                        productionConfigVersion
                    }) {
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("开发配置不能为空");
            }
            if (version < 0 || repository.contains(NEWLINE) || repository.contains(RETURN)) {
                throw new IllegalArgumentException("开发配置无效");
            }
            // HTTP仓库禁止嵌入凭据；SSH仅允许用户名，凭据由部署侧提供。
            if (repository.contains(URI_SEPARATOR)) {
                var uri = java.net.URI.create(repository);
                if (uri.getQuery() != null
                        || uri.getFragment() != null
                        || uri.getUserInfo() != null
                                && (!SSH.equalsIgnoreCase(uri.getScheme())
                                        || uri.getUserInfo().contains(COLON)))
                    throw new IllegalArgumentException("仓库地址不能包含凭据、查询参数或片段");
            }
        }

        public DevelopmentConfig atVersion(long value) {
            return new DevelopmentConfig(
                    repository,
                    previewBinding,
                    previewConfigVersion,
                    productionBinding,
                    productionConfigVersion,
                    value);
        }
    }

    /** baselineSha 是绑定基线；配置在受理动作时另行固定，不随后续项目配置变化。 */
    public record DevelopmentContext(
            /** 固定会话身份；分支创建使用项目操作域。 */
            String sessionId,
            /** Agent项目身份。 */
            String projectId,
            /** 此会话固定仓库。 */
            String repository,
            /** 真实Git短分支名。 */
            String branchName,
            /** 创建会话观察到的完整提交。 */
            String baselineSha,
            /** 当前动作固定的环境配置。 */
            DevelopmentConfig configSnapshot) {}

    /** 两种 SHA 只在外部事实得到核实后出现；buildIds 保留本动作所有显式尝试。 */
    public record OperationView(
            /** 持久操作身份。 */
            UUID operationId,
            /** 用户明确触发的动作。 */
            OperationType type,
            /** 可恢复的推进阶段。 */
            Phase phase,
            /** 独立操作状态。 */
            OperationStatus status,
            /** 显式恢复使用的CAS版本。 */
            long version,
            /** 已核实push的源码提交。 */
            String pushedSha,
            /** 已核实合并的实际提交。 */
            String mergeSha,
            /** 所有构建尝试的不可变身份。 */
            List<UUID> buildIds,
            /** 稳定诊断。 */
            OperationError error) {}

    public record OperationError(
            /** 稳定错误码。 */
            String code,
            /** 安全展示信息。 */
            String message,
            /** 是否可显式恢复原操作。 */
            boolean retryable) {}

    /** expectedRevision 固定用户确认的本地文件修订；保存成功仅证明 Git 已接收。 */
    public record SaveRequest(
            /** 同一保存意图的请求键。 */
            String idempotencyKey,
            /** 待保存本地修订。 */
            long expectedRevision,
            /** Git提交说明。 */
            String commitMessage)
            implements StrictDevelopmentRequest {
        public SaveRequest {
            requireKey(idempotencyKey);
            requireRevision(expectedRevision);
            if (commitMessage == null || commitMessage.isBlank())
                throw new IllegalArgumentException("需要提交说明");
        }
    }

    /** 有本地修改时才要求 commitMessage；不能用配置变化改写原操作输入。 */
    public record PreviewRequest(
            /** 同一预览意图的请求键。 */
            String idempotencyKey,
            /** 保存阶段预期修订。 */
            long expectedRevision,
            /** 有本地修改时的提交说明。 */
            String commitMessage)
            implements StrictDevelopmentRequest {
        public PreviewRequest {
            requireKey(idempotencyKey);
            requireRevision(expectedRevision);
        }
    }

    /** 用户确认覆盖共享分支全部差异及环境配置；两端 SHA 有变化必须重新确认。 */
    public record DeliverRequest(
            /** 同一交付意图的请求键。 */
            String idempotencyKey,
            /** 用户确认的共享分支HEAD。 */
            String expectedSourceSha,
            /** 用户确认的main HEAD。 */
            String expectedMainSha,
            /** 共享差异与配置的确认摘要。 */
            String confirmedScopeDigest)
            implements StrictDevelopmentRequest {
        public DeliverRequest {
            requireKey(idempotencyKey);
            requireSha(expectedSourceSha);
            requireSha(expectedMainSha);
            if (confirmedScopeDigest == null || !confirmedScopeDigest.matches(DIGEST_PATTERN)) {
                throw new IllegalArgumentException("需要完整交付范围摘要");
            }
        }
    }

    /** expectedVersion 防止过期恢复覆盖新状态；幂等键固定一次显式恢复尝试。 */
    public record RecoverRequest(
            /** 操作事实预期版本。 */
            long expectedVersion,
            /** 同一次显式恢复的请求键。 */
            String idempotencyKey)
            implements StrictDevelopmentRequest {
        public RecoverRequest {
            requireRevision(expectedVersion);
            requireKey(idempotencyKey);
        }
    }

    public record BranchRequest(
            /** 要创建的非main短分支名。 */
            String branchName,
            /** 同一分支创建意图的请求键。 */
            String idempotencyKey)
            implements StrictDevelopmentRequest {
        public BranchRequest {
            requireKey(idempotencyKey);
            requireBranch(branchName);
        }
    }

    public record DevelopmentSelection(
            /** 用户选择的Agent项目。 */
            String projectId,
            /** 用户选择的真实开发分支。 */
            String branchName)
            implements StrictDevelopmentRequest {
        public DevelopmentSelection {
            requireBranch(branchName);
            if (projectId == null || projectId.isBlank())
                throw new IllegalArgumentException("需要Agent项目");
        }
    }

    private static void requireBranch(String branch) {
        if (branch == null
                || branch.isBlank()
                || MAIN.equals(branch)
                || branch.startsWith(REFS)
                || branch.startsWith(DASH)
                || branch.contains(DOTS)
                || branch.contains(REFLOG)
                || branch.matches(INVALID_BRANCH)
                || branch.endsWith(SLASH)
                || branch.endsWith(DOT)) throw new IllegalArgumentException("需要非main的Git短分支名");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 256)
            throw new IllegalArgumentException("幂等键无效");
    }

    private static void requireRevision(long value) {
        if (value < 0) throw new IllegalArgumentException("版本不能为负数");
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("必须使用完整提交 SHA");
        }
    }
}
