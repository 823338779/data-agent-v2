package com.datagent.agentv2.controller;

import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryException;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemorySettingsView;
import com.datagent.agentv2.memory.MemoryStatus;
import com.datagent.agentv2.memory.PersonalMemoryApplicationService;
import com.datagent.agentv2.memory.PersonalMemoryListView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 固定身份的个人记忆管理 HTTP 门面；请求不能提供租户、用户、审查模式或证据来源。 */
@RestController
@RequestMapping("/api/user/memories")
@RequiredArgsConstructor
public class PersonalMemoryController {

    private final PersonalMemoryApplicationService application;

    @GetMapping
    public PersonalMemoryListView list(
            @RequestParam(name = "kind", required = false) @Size(min = 1, max = 64)
                    String kind,
            @RequestParam(required = false) @Size(min = 1, max = 128) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return application.list(parseKind(kind), cursor, limit);
    }

    @PostMapping
    public ResponseEntity<MemoryOutcome> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String requestId,
            @Valid @RequestBody CreateRequest request) {
        MemoryOutcome outcome =
                application.create(
                        requestId,
                        request.kind(),
                        request.content(),
                        Boolean.TRUE.equals(request.restore()));
        return outcome.status() == MemoryStatus.CREATED
                ? ResponseEntity.status(201).body(outcome)
                : ResponseEntity.ok(outcome);
    }

    @PutMapping("/{id}")
    public MemoryOutcome replace(
            @PathVariable @NotBlank @Size(max = 128) String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String requestId,
            @Valid @RequestBody ReplaceRequest request) {
        return application.replace(
                requestId, id, request.expectedVersion(), request.content());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @PathVariable @NotBlank @Size(max = 128) String id,
            @RequestParam @Min(1) long expectedVersion,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String requestId) {
        application.remove(requestId, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public MemorySettingsView settings() {
        return application.settings();
    }

    @PutMapping("/settings")
    public MemorySettingsView updateSettings(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 256) String requestId,
            @Valid @RequestBody SettingsRequest request) {
        return application.updateSettings(
                requestId,
                request.expectedVersion(),
                request.readEnabled(),
                request.explicitWriteEnabled(),
                request.autoLearningEnabled());
    }

    /** 正文容量由统一服务在去重后判断，HTTP 只校验非空形状。 */
    public record CreateRequest(
            @NotNull MemoryKind kind, @NotBlank String content, Boolean restore) {}

    /** 条目 CAS 版本从 1 开始，避免非法值进入领域命令构造器。 */
    public record ReplaceRequest(
            @NotBlank String content, @NotNull @Min(1) Long expectedVersion) {}

    /** 设置 CAS 初始版本为 0；可空开关表示该字段保持原值。 */
    public record SettingsRequest(
            @NotNull @Min(0) Long expectedVersion,
            Boolean readEnabled,
            Boolean explicitWriteEnabled,
            Boolean autoLearningEnabled) {}

    private static MemoryKind parseKind(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MemoryKind.parse(value);
        } catch (IllegalArgumentException failure) {
            throw new MemoryException(MemoryStatus.INVALID_REQUEST, "记忆分类无效");
        }
    }
}
