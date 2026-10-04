package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.model.GitWorkspaceView;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 工作区创建及会话关联 BFF；关联仅为可选上下文，不代表人员权限。 */
@RestController
@ConditionalOnExpression("${data-agent.dbt.enabled:false} and ${data-agent.git.enabled:false}")
@RequiredArgsConstructor
public class GitWorkspaceController {
    private static final String COLLECTION = "/api/v1/git-workspaces";
    private static final String DETAIL = COLLECTION + "/{workspaceId}";
    private static final String RESTART = DETAIL + "/restart";
    private static final String ATTACH =
            "/api/v1/agent-sessions/{sessionId}/git-workspaces/{workspaceId}";
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    private final GitWorkspaceService workspaces;
    private final ConversationSessionService sessions;

    @PostMapping(COLLECTION)
    public GitWorkspaceView create(@Valid @RequestBody CreateRequest request) {
        requireSession(request.sessionId());
        return workspaces.create(
                request.sessionId(),
                request.branchId(),
                request.baseCommitSha(),
                request.idempotencyKey());
    }

    @GetMapping(COLLECTION)
    public List<GitWorkspaceView> list(@RequestParam String sessionId) {
        requireSession(sessionId);
        return workspaces.listForSession(sessionId);
    }

    @GetMapping(DETAIL)
    public GitWorkspaceView get(@PathVariable String workspaceId) {
        return workspaces.get(workspaceId);
    }

    /** 显式恢复损坏或中断草稿，原目录保留；未决提交仍由业务层拒绝重启。 */
    @PostMapping(RESTART)
    public GitWorkspaceView restart(
            @PathVariable String workspaceId, @Valid @RequestBody RestartRequest request) {
        return workspaces.prepare(workspaceId, true, request.expectedRevision());
    }

    public record RestartRequest(
            /** 用户查看材料时的草稿修订号。 */
            @NotNull @Min(0) Long expectedRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("恢复请求包含未知字段");
        }
    }

    @PutMapping(ATTACH)
    public GitWorkspaceView attach(
            @PathVariable String sessionId, @PathVariable String workspaceId) {
        requireSession(sessionId);
        return workspaces.attach(sessionId, workspaceId);
    }

    private void requireSession(String sessionId) {
        if (sessions.findByUuid(sessionId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    /** 创建独立草稿的固定输入；同键改变分支或基线返回冲突。 */
    public record CreateRequest(
            /** 首次关联的会话，不构成独占绑定。 */
            @NotBlank @Size(max = 128) String sessionId,
            /** 服务登记的稳定分支实例。 */
            @NotBlank @Size(max = 128) String branchId,
            /** 该分支历史内已核实的完整 SHA。 */
            @NotBlank @Pattern(regexp = SHA_PATTERN) String baseCommitSha,
            /** 创建会话内唯一的请求键。 */
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("工作区请求包含未知字段");
        }
    }
}
