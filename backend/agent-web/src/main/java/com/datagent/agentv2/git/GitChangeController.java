package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.model.*;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 历史候选只读审阅入口；旧确认、重试、取消与核实写路由均已退役。 */
@RestController
@RequestMapping("/api/v1/agent-sessions/{sessionId}/git-changes")
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GitChangeController {
    private final GitChangeService changes;

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

    private GitChangeResponse response(String session, GitChangeView view) {
        try {
            return GitChangeResponse.composeFromChange(
                    view, changes.files(session, view.changeId()));
        } catch (GitException failure) {
            // 归属已校验；已知审阅损坏只影响审阅能力，不得改写或遮蔽持久化提交事实。
            if (!"GIT_SNAPSHOT_CORRUPT".equals(failure.code())) {
                throw failure;
            }
            return GitChangeResponse.composeFromUnavailableReview(view);
        }
    }

    /** 完整冻结文件 diff；响应大小受候选 maxDiffBytes 配置硬上限约束。 */
    public record DiffResponse(String fileId, String diff) {}
}
