package com.datagent.agentv2.development;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.git.model.BranchInfo;
import com.datagent.agentv2.persistence.entity.development.DevelopmentProjectEntity;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Agent项目的开发配置及真实Git分支门面，不向dbt-service注册项目或分支。 */
@RestController
@RequestMapping(DevelopmentProjectController.PROJECTS)
@RequiredArgsConstructor
public class DevelopmentProjectController {
    public static final String PROJECTS = "/api/v1/agent-projects";
    private static final String CONFIG = "/{projectId}/development-config",
            BRANCHES = "/{projectId}/branches";

    /** 普通项目分页。 */
    private final ConversationProjectService projects;

    /** 开发配置事实。 */
    private final DevelopmentContextPersistenceService records;

    /** 配置用例。 */
    private final DevelopmentContextService contexts;

    /** Git关闭时普通项目仍然可用。 */
    private final ObjectProvider<DevelopmentBranchService> branches;

    @GetMapping
    public ProjectPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("分页范围必须为1至200");
        var rows = projects.pageAfter(cursor, limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.stream().limit(limit).toList();
        return new ProjectPage(
                selected.stream()
                        .map(
                                project ->
                                        ProjectView.compose(
                                                project, records.project(project.projectId())))
                        .toList(),
                more ? selected.getLast().projectId() : null);
    }

    @GetMapping(CONFIG)
    public DevelopmentConfig config(@PathVariable String projectId) {
        return contexts.config(projectId);
    }

    @PutMapping(CONFIG)
    public DevelopmentConfig configure(
            @PathVariable String projectId, @RequestBody ConfigureRequest request) {
        return contexts.configure(projectId, request.config(), request.expectedVersion());
    }

    @GetMapping(BRANCHES)
    public BranchPage branches(@PathVariable String projectId) {
        return new BranchPage(branches().list(projectId));
    }

    @PostMapping(BRANCHES)
    public ResponseEntity<OperationView> createBranch(
            @PathVariable String projectId, @RequestBody BranchRequest request) {
        return ResponseEntity.accepted().body(branches().create(projectId, request));
    }

    private DevelopmentBranchService branches() {
        var service = branches.getIfAvailable();
        if (service == null)
            throw new DevelopmentException("DEVELOPMENT_UNAVAILABLE", "Git开发能力未启用", 503);
        return service;
    }

    public record ConfigureRequest(
            /** 受控仓库。 */
            String repository,
            /** 预览绑定。 */
            String previewBinding,
            /** 预览语义版本。 */
            String previewConfigVersion,
            /** 用户显式指定的预览目标 schema。 */
            String previewSchemaName,
            /** 正式绑定。 */
            String productionBinding,
            /** 正式语义版本。 */
            String productionConfigVersion,
            /** 用户显式指定的正式目标 schema。 */
            String productionSchemaName,
            /** 配置CAS版本。 */
            long expectedVersion)
            implements com.datagent.agentv2.development.model.StrictDevelopmentRequest {
        DevelopmentConfig config() {
            return new DevelopmentConfig(
                    repository,
                    previewBinding,
                    previewConfigVersion,
                    previewSchemaName,
                    productionBinding,
                    productionConfigVersion,
                    productionSchemaName,
                    expectedVersion);
        }
    }

    public record ProjectPage(
            /** 本页项目。 */
            List<ProjectView> items,
            /** 后续游标。 */
            String nextCursor) {}

    public record BranchPage(
            /** 真实Git分支。 */
            List<BranchInfo> items) {}

    public record ProjectView(
            /** Agent项目身份。 */
            String projectId,
            /** 展示名。 */
            String name,
            /** 项目版本。 */
            long version,
            /** 已有开发配置。 */
            boolean developmentConfigured,
            /** 受控仓库展示地址。 */
            String repository) {
        static ProjectView compose(ConversationProject project, DevelopmentProjectEntity config) {
            return new ProjectView(
                    project.projectId(),
                    project.name(),
                    project.version(),
                    config != null,
                    config == null ? null : config.getRepository());
        }
    }
}
