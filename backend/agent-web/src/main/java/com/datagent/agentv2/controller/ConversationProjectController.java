package com.datagent.agentv2.controller;

import com.datagent.agentv2.conversation.project.ConversationProjectApplicationService;
import com.datagent.agentv2.conversation.project.ConversationProjectView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 项目分组的 HTTP 门面；会话内容与执行不归此控制器管理。 */
@RestController
@RequestMapping("/api/v1/agent-projects")
@RequiredArgsConstructor
public class ConversationProjectController {
    private final ConversationProjectApplicationService projects;

    @PostMapping
    ResponseEntity<ConversationProjectView> create(
            @Valid @RequestBody CreateProjectRequest request) {
        var project = projects.create(request.name());
        return ResponseEntity.created(URI.create("/api/v1/agent-projects/" + project.projectId()))
                .body(ConversationProjectView.composeFromProject(project));
    }

    @PatchMapping("/{projectId}")
    ConversationProjectView rename(
            @PathVariable String projectId, @Valid @RequestBody RenameProjectRequest request) {
        return ConversationProjectView.composeFromProject(
                projects.rename(projectId, request.name(), request.expectedVersion()));
    }

    @DeleteMapping("/{projectId}")
    ResponseEntity<Void> delete(
            @PathVariable String projectId, @RequestParam @Min(0) long expectedVersion) {
        projects.delete(projectId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    // 最大长度由领域层在 trim 后校验；HTTP 入口与非 HTTP 入口遵守同一规则。
    record CreateProjectRequest(@NotBlank String name) {}

    record RenameProjectRequest(@NotBlank String name, @NotNull @Min(0) Long expectedVersion) {}
}
