package com.datagent.agentv2.dbt.application;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.git.port.GitBranchRegistry;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 固定 dbt HTTP 项目上的分支登记，能力不足时禁止退回 main 写入。 */
@RequiredArgsConstructor
public final class DbtGitBranchRegistry implements GitBranchRegistry {
    private static final String CAPABILITIES = "capabilities";
    private static final String BRANCH_CAPABILITY = "branch-development-v1";
    private static final String PROTOCOL = "protocolVersion";
    private static final String VERSION = "agent-dbt-v1";
    private static final String NOT_CONFIGURED = "GIT_NOT_CONFIGURED";
    private static final String CONFLICT = "GIT_CONFLICT";
    private static final String NOT_FOUND = "GIT_WORKSPACE_NOT_FOUND";
    private static final String UNAVAILABLE = "GIT_COMMAND_FAILED";
    private static final String DBT_CONFLICT = "DBT_CONFLICT";
    private static final String DBT_NOT_FOUND = "DBT_NOT_FOUND";
    private final DbtServiceClient client;

    @Override
    public JsonNode list() {
        return call(client::branches, false);
    }

    @Override
    public JsonNode get(String branchId) {
        return call(() -> client.branch(branchId), false);
    }

    @Override
    public JsonNode releases(String branchId) {
        return call(() -> client.releases(branchId), false);
    }

    @Override
    public JsonNode release(String branchId, String releaseId) {
        return call(() -> client.release(branchId, releaseId), false);
    }

    @Override
    public JsonNode create(JsonNode request) {
        return call(() -> client.createBranch(request), true);
    }

    @Override
    public JsonNode register(JsonNode request) {
        return call(() -> client.registerBranch(request), true);
    }

    @Override
    public JsonNode delete(String branchId, long expectedVersion) {
        return call(() -> client.deleteBranch(branchId, expectedVersion), true);
    }

    private JsonNode call(Callable<JsonNode> operation, boolean mutation) {
        try {
            // 写请求前核实协议，避免新 Agent 误将旧服务的生产能力当作分支能力。
            if (mutation) {
                JsonNode project = client.project();
                boolean supported = false;
                for (JsonNode value : project.path(CAPABILITIES)) {
                    supported |= BRANCH_CAPABILITY.equals(value.asString());
                }
                if (!supported || !VERSION.equals(project.path(PROTOCOL).asString())) {
                    throw new GitException(NOT_CONFIGURED, "dbt 服务尚未提供分支开发能力");
                }
            }
            return operation.call();
        } catch (GitException failure) {
            throw failure;
        } catch (ToolBusinessException failure) {
            String code =
                    DBT_CONFLICT.equals(failure.code())
                            ? CONFLICT
                            : DBT_NOT_FOUND.equals(failure.code()) ? NOT_FOUND : UNAVAILABLE;
            throw new GitException(code, "分支服务操作未完成，请刷新状态后重试");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitException(UNAVAILABLE, "分支服务请求被中断");
        } catch (Exception failure) {
            throw new GitException(UNAVAILABLE, "分支服务暂不可用");
        }
    }
}
