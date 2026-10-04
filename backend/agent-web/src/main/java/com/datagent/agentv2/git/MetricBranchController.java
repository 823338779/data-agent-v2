package com.datagent.agentv2.git;

import com.datagent.agentv2.git.port.GitBranchRegistry;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 受控项目的分支目录代理，分支身份与生命周期均由 dbt 服务维护。 */
@RestController
@RequestMapping(MetricBranchController.COLLECTION)
@ConditionalOnExpression("${data-agent.dbt.enabled:false} and ${data-agent.git.enabled:false}")
@RequiredArgsConstructor
public class MetricBranchController {
    static final String COLLECTION = "/api/v1/metric-branches";
    private static final String REGISTER = "/register";
    private static final String DETAIL = "/{branchId}";
    private static final String RELEASES = DETAIL + "/releases";
    private static final String RELEASE = RELEASES + "/{releaseId}";
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    private final GitBranchRegistry branches;
    private final ObjectMapper json;

    @GetMapping
    public JsonNode list() {
        return branches.list();
    }

    @GetMapping(DETAIL)
    public JsonNode get(@PathVariable String branchId) {
        return branches.get(branchId);
    }

    @GetMapping(RELEASES)
    public JsonNode releases(@PathVariable String branchId) {
        return branches.releases(branchId);
    }

    @GetMapping(RELEASE)
    public JsonNode release(@PathVariable String branchId, @PathVariable String releaseId) {
        return branches.release(branchId, releaseId);
    }

    @PostMapping
    public JsonNode create(@Valid @RequestBody CreateRequest request) {
        return branches.create(json.valueToTree(request));
    }

    @PostMapping(REGISTER)
    public JsonNode register(@Valid @RequestBody RegisterRequest request) {
        return branches.register(json.valueToTree(request));
    }

    @DeleteMapping(DETAIL)
    public JsonNode delete(@PathVariable String branchId, @RequestParam long expectedVersion) {
        if (expectedVersion < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本不能为负数");
        }
        return branches.delete(branchId, expectedVersion);
    }

    /** 从已登记分支的固定提交创建新分支。 */
    public record CreateRequest(
            /** 不带 refs/heads/ 前缀的分支名称。 */
            @NotBlank @Size(max = 200) String name,
            /** 来源分支实例。 */
            @NotBlank @Size(max = 128) String sourceBranchId,
            /** 来源分支可达的完整提交。 */
            @NotBlank @Pattern(regexp = SHA_PATTERN) String sourceCommitSha,
            /** dbt 服务内的幂等请求键。 */
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("分支请求包含未知字段");
        }
    }

    /** 登记已有远端引用，服务端仍需核实固定基线。 */
    public record RegisterRequest(
            /** 已存在的短分支名称。 */
            @NotBlank @Size(max = 200) String name,
            /** 已有分支可达的完整基线。 */
            @NotBlank @Pattern(regexp = SHA_PATTERN) String baseCommitSha,
            /** dbt 服务内的幂等请求键。 */
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("分支请求包含未知字段");
        }
    }
}
