package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.GitWorkspaceSyncService;
import com.datagent.agentv2.git.model.GitSyncResult;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

/** 同步只能作用于显式工作区及预期草稿修订，不能接受远端或目录覆盖。 */
@RestController
@ConditionalOnExpression("${data-agent.dbt.enabled:false} and ${data-agent.git.enabled:false}")
@RequiredArgsConstructor
public class GitWorkspaceSyncController {
    private static final String ROUTE = "/api/v1/git-workspaces/{workspaceId}/sync";
    private static final String RESOLVE = ROUTE + "/resolve";
    private final GitWorkspaceSyncService synchronization;

    @PostMapping(ROUTE)
    public GitSyncResult sync(
            @PathVariable String workspaceId, @Valid @RequestBody SyncRequest request) {
        return synchronization.sync(workspaceId, request.expectedRevision());
    }

    @PostMapping(RESOLVE)
    public GitSyncResult resolve(
            @PathVariable String workspaceId, @Valid @RequestBody ResolveRequest request) {
        return synchronization.resolve(
                workspaceId,
                request.expectedRevision(),
                request.remoteCommitSha(),
                request.resolutions());
    }

    /** 所有冲突的人工正文，null 表示删除；不能提供物理目录或 Git tree。 */
    public record ResolveRequest(
            /** 冲突产生时的草稿修订。 */
            @NotNull @Min(0) Long expectedRevision,
            /** 冲突产生时实际观测的远端提交。 */
            @NotBlank @Pattern(regexp = "[0-9a-f]{40}") String remoteCommitSha,
            /** 相对路径到完整人工合并正文，必须恰好覆盖冲突路径。 */
            @NotNull @Size(min = 1, max = 100) Map<String, String> resolutions) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("人工解决请求包含未知字段");
        }
    }

    /** 必须提供当前草稿内容修订号，不能使用候选 CAS 版本。 */
    public record SyncRequest(@NotNull @Min(0) Long expectedRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("同步请求包含未知字段");
        }
    }
}
