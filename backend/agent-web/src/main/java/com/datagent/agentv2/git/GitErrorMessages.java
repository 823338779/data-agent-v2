package com.datagent.agentv2.git;

/** 将稳定错误码映射为安全说明，不向用户透传异常消息、远端地址或命令输出。 */
public final class GitErrorMessages {
    private GitErrorMessages() {}

    public static String summary(String code) {
        if (code == null) return null;
        return switch (code) {
            case "GIT_CONFLICT", "GIT_CONTENT_CONFLICT" -> "草稿或候选版本已变化，请刷新后重试。";
            case "GIT_BASE_CHANGED", "GIT_NON_FAST_FORWARD" -> "远端 main 已变化，需要基于最新内容重新准备并确认。";
            case "GIT_CHANGE_NOT_FOUND", "GIT_FILE_NOT_FOUND", "GIT_WORKSPACE_NOT_FOUND" ->
                    "当前会话的仓库资源不存在。";
            case "GIT_NOT_CONFIGURED" -> "Git 仓库尚未配置，请联系服务管理员。";
            case "GIT_LIMIT_EXCEEDED", "GIT_FILE_TOO_LARGE", "GIT_OUTPUT_TOO_LARGE" ->
                    "仓库内容超过服务端资源上限，请缩小变更范围。";
            case "GIT_TIMEOUT", "GIT_COMMAND_FAILED", "GIT_RESULT_UNKNOWN" ->
                    "Git 操作未完成或远端暂不可达，请稍后检查或核实结果。";
            case "GIT_RETRY_REQUIRED", "GIT_TASK_REJECTED" -> "提交任务未完成，可通过卡片重试。";
            case "GIT_WORKSPACE_CORRUPT",
                    "GIT_WORKSPACE_INVALID",
                    "GIT_SNAPSHOT_CORRUPT",
                    "GIT_COMMIT_CORRUPT" ->
                    "草稿或候选完整性检查失败，需要重新准备。";
            case "GIT_INVALID_REQUEST",
                    "GIT_PATH_REJECTED",
                    "GIT_BINARY_FILE",
                    "GIT_EMPTY_CHANGE" ->
                    "请求内容无效、不受支持或没有文件变更。";
            default -> "Git 操作未完成，请刷新候选状态或联系服务管理员。";
        };
    }
}
