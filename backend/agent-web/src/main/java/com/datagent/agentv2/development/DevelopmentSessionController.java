package com.datagent.agentv2.development;

import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.development.model.DevelopmentOverview;
import com.datagent.agentv2.git.model.FileOperation;
import com.datagent.agentv2.git.model.FileReadResult;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 固定会话开发入口，HTTP只调用与模型工具共用的持久动作，不绑定SSE生命周期。 */
@RestController
@RequestMapping(DevelopmentSessionController.PATH)
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevelopmentSessionController {
    public static final String PATH = "/api/v1/agent-sessions/{sessionId}/development";
    private static final String SAVE = "/save",
            PREVIEW = "/preview",
            DELIVER = "/deliver",
            OPERATION = "/operations/{operationId}",
            RECOVER = OPERATION + "/recover",
            FILES = "/files",
            FILE = "/file",
            DIFF = "/diff";

    /** Git保存用例。 */
    private final SourceSaveService saves;

    /** 预览受理用例。 */
    private final ObjectProvider<PreviewBuildService> previews;

    /** 正式交付用例。 */
    private final ObjectProvider<DeliveryService> deliveries;

    /** 四组独立状态。 */
    private final ObjectProvider<DevelopmentStatusService> statuses;

    /** 统一恢复和归属验证。 */
    private final DevelopmentRecoveryRunner recovery;

    /** 受控会话文件。 */
    private final DevelopmentWorkspaceService files;

    @GetMapping
    public DevelopmentOverview overview(@PathVariable String sessionId) {
        return available(statuses).getOverview(sessionId);
    }

    @PostMapping(SAVE)
    public ResponseEntity<OperationView> save(
            @PathVariable String sessionId, @RequestBody SaveRequest request) {
        return ResponseEntity.accepted().body(saves.start(sessionId, request));
    }

    @PostMapping(PREVIEW)
    public ResponseEntity<OperationView> preview(
            @PathVariable String sessionId, @RequestBody PreviewRequest request) {
        return ResponseEntity.accepted().body(available(previews).start(sessionId, request));
    }

    @PostMapping(DELIVER)
    public ResponseEntity<OperationView> deliver(
            @PathVariable String sessionId, @RequestBody DeliverRequest request) {
        return ResponseEntity.accepted().body(available(deliveries).start(sessionId, request));
    }

    @GetMapping(OPERATION)
    public OperationView operation(@PathVariable String sessionId, @PathVariable UUID operationId) {
        return recovery.getOperation(sessionId, operationId);
    }

    @PostMapping(RECOVER)
    public ResponseEntity<OperationView> recover(
            @PathVariable String sessionId,
            @PathVariable UUID operationId,
            @RequestBody RecoverRequest request) {
        return ResponseEntity.accepted().body(recovery.recover(sessionId, operationId, request));
    }

    @GetMapping(FILES)
    public FilesView list(
            @PathVariable String sessionId, @RequestParam(defaultValue = ".") String directory) {
        return files.locked(
                sessionId,
                () ->
                        new FilesView(
                                files.list(sessionId, directory), files.getRevision(sessionId)));
    }

    @GetMapping(FILE)
    public FileReadResult read(@PathVariable String sessionId, @RequestParam String path) {
        return files.read(sessionId, path);
    }

    @PostMapping(FILES)
    public RevisionView write(@PathVariable String sessionId, @RequestBody FilesRequest request) {
        return new RevisionView(
                files.apply(sessionId, request.expectedRevision(), request.operations()));
    }

    @GetMapping(DIFF)
    public DiffView diff(@PathVariable String sessionId) {
        return files.locked(
                sessionId,
                () -> new DiffView(files.diff(sessionId).diff(), files.getRevision(sessionId)));
    }

    private static <T> T available(ObjectProvider<T> provider) {
        T value = provider.getIfAvailable();
        if (value == null)
            throw new DevelopmentException("DEVELOPMENT_UNAVAILABLE", "dbt开发能力未启用", 503);
        return value;
    }

    public record FilesView(
            /** 受控工程文件。 */
            List<String> files,
            /** 当前本地修订。 */
            long revision) {}

    public record FilesRequest(
            /** 受保护本地修订。 */
            long expectedRevision,
            /** 全文件结构化改动。 */
            List<FileOperation> operations)
            implements com.datagent.agentv2.development.model.StrictDevelopmentRequest {
        public FilesRequest {
            // 在进入持久MUTATING屏障之前拒绝结构不完整的文件操作。
            if (expectedRevision < 0
                    || operations == null
                    || operations.isEmpty()
                    || operations.stream()
                            .anyMatch(
                                    operation ->
                                            operation == null
                                                    || operation.type() == null
                                                    || operation.path() == null
                                                    || operation.path().isBlank())) {
                throw new IllegalArgumentException("文件操作必须包含类型、相对路径和有效修订");
            }
        }
    }

    public record RevisionView(
            /** 更新后的本地修订。 */
            long revision) {}

    public record DiffView(
            /** 完整本地差异。 */
            String diff,
            /** 当前修订。 */
            long revision) {}
}
