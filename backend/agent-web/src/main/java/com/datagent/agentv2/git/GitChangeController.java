package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 会话候选审阅与按钮入口；直接调用提交服务，不启动模型执行。 */
@RestController
@RequestMapping("/api/v1/agent-sessions/{sessionId}/git-changes")
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GitChangeController {
    private final GitChangeService changes;
    private final GitSubmissionService submissions;

    @GetMapping("/{changeId}")
    public GitChangeResponse detail(@PathVariable String sessionId, @PathVariable String changeId) {
        return response(sessionId, changes.detail(sessionId, changeId));
    }

    @GetMapping("/{changeId}/files")
    public List<GitChangeResponse.FileSummary> files(
            @PathVariable String sessionId, @PathVariable String changeId) {
        return changes.files(sessionId, changeId).stream()
                .map(f -> new GitChangeResponse.FileSummary(f.fileId(), f.status(), f.path()))
                .toList();
    }

    @GetMapping("/{changeId}/diff")
    public DiffResponse diff(
            @PathVariable String sessionId,
            @PathVariable String changeId,
            @RequestParam String fileId) {
        return new DiffResponse(fileId, changes.diff(sessionId, changeId, fileId));
    }

    @PostMapping("/{changeId}/confirm")
    public ResponseEntity<GitChangeResponse> confirm(
            @PathVariable String sessionId,
            @PathVariable String changeId,
            @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.accepted()
                .body(
                        response(
                                sessionId,
                                submissions.confirm(sessionId, changeId, request.version())));
    }

    @PostMapping("/{changeId}/cancel")
    public GitChangeResponse cancel(
            @PathVariable String sessionId,
            @PathVariable String changeId,
            @Valid @RequestBody VersionRequest request) {
        return response(sessionId, submissions.cancel(sessionId, changeId, request.version()));
    }

    @PostMapping("/{changeId}/retry")
    public ResponseEntity<GitChangeResponse> retry(
            @PathVariable String sessionId,
            @PathVariable String changeId,
            @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.accepted()
                .body(
                        response(
                                sessionId,
                                submissions.retry(sessionId, changeId, request.version())));
    }

    @PostMapping("/{changeId}/reconcile")
    public GitChangeResponse reconcile(
            @PathVariable String sessionId,
            @PathVariable String changeId,
            @Valid @RequestBody VersionRequest request) {
        // 核实只读取远端，不追加提交许可；版本检查提醒客户端刷新过期卡片。
        if (changes.detail(sessionId, changeId).version() != request.version()) {
            throw new GitException("GIT_CONFLICT", "候选版本已变化");
        }
        return response(sessionId, submissions.reconcile(sessionId, changeId));
    }

    private GitChangeResponse response(String session, GitChangeView view) {
        try {
            return GitChangeResponse.composeFromChange(
                    view, changes.files(session, view.changeId()));
        } catch (GitException failure) {
            // 归属和按钮状态已校验；已知审阅损坏只影响审阅能力，不得改写或遮蔽持久化提交事实。
            if (!"GIT_SNAPSHOT_CORRUPT".equals(failure.code())) {
                throw failure;
            }
            return GitChangeResponse.composeFromUnavailableReview(view);
        }
    }

    /** 版本由最新详情提供，0 是合法的首次候选版本。 */
    public record VersionRequest(@NotNull @PositiveOrZero Long version) {}

    /** 完整冻结文件 diff；响应大小受候选 maxDiffBytes 配置硬上限约束。 */
    public record DiffResponse(String fileId, String diff) {}
}
