package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.persistence.entity.development.DevelopmentOperationEntity;
import com.datagent.agentv2.persistence.service.development.DevelopmentOperationPersistenceService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 共用受理和 CAS，不执行 Git/构建业务；重试首先核对原输入，再检查新动作资格。 */
@Service
@RequiredArgsConstructor
public class DevelopmentOperations {
    private static final String CONFLICT = "DEVELOPMENT_CONFLICT";
    private static final String NOT_FOUND = "DEVELOPMENT_NOT_FOUND";
    private static final String HASH = "SHA-256";

    /** 受理时固定的可信会话配置。 */
    private final DevelopmentContextService contexts;

    /** 幂等键与推进版本的数据库边界。 */
    private final DevelopmentOperationPersistenceService records;

    /** 请求与上下文使用相同的稳定JSON编码。 */
    private final ObjectMapper json;

    public DevelopmentOperationEntity existing(
            String session, OperationType type, String key, Object request) {
        var row = records.byKey(session, type.name(), key);
        if (row != null
                && !Objects.equals(
                        row.getRequestDigest(), digest(json.writeValueAsString(request)))) {
            throw new DevelopmentException(CONFLICT, "同一操作键不能改变输入", 409);
        }
        return row;
    }

    public DevelopmentOperationEntity accept(
            String session, OperationType type, String key, Object request, Phase phase) {
        var prior = existing(session, type, key, request);
        if (prior != null) return prior;
        var context = contexts.forNewOperation(session);
        return acceptFrozen(context, type, key, request, phase);
    }

    /** 分支创建使用项目操作域，其他动作只能传入可信会话上下文。 */
    public DevelopmentOperationEntity acceptFrozen(
            DevelopmentContext context,
            OperationType type,
            String key,
            Object request,
            Phase phase) {
        String session = context.sessionId();
        var row = new DevelopmentOperationEntity();
        row.setOperationId(UUID.randomUUID().toString());
        row.setSessionId(session);
        row.setProjectId(context.projectId());
        row.setType(type.name());
        row.setIdempotencyKey(key);
        row.setRequestJson(json.writeValueAsString(request));
        row.setRequestDigest(digest(row.getRequestJson()));
        row.setContextJson(json.writeValueAsString(context));
        row.setPhase(phase.name());
        row.setStatus(OperationStatus.RUNNING.name());
        row.setVersion(0L);
        row.setAttemptNumber(0);
        try {
            return records.acceptConfigured(row, context.configSnapshot().version());
        } catch (IllegalStateException e) {
            throw new DevelopmentException(CONFLICT, e.getMessage(), 409);
        }
    }

    public DevelopmentOperationEntity require(UUID id) {
        var row = records.find(id.toString());
        if (row == null) throw new DevelopmentException(NOT_FOUND, "操作不存在", 404);
        return row;
    }

    public DevelopmentOperationEntity owned(String session, UUID id) {
        var row = require(id);
        if (!row.getSessionId().equals(session))
            throw new DevelopmentException(NOT_FOUND, "操作不存在", 404);
        return row;
    }

    public DevelopmentContext context(DevelopmentOperationEntity row) {
        return json.readValue(row.getContextJson(), DevelopmentContext.class);
    }

    public <T> T request(DevelopmentOperationEntity row, Class<T> type) {
        return json.readValue(row.getRequestJson(), type);
    }

    public DevelopmentOperationEntity update(
            DevelopmentOperationEntity row, Phase phase, OperationStatus status, String error) {
        row.setPhase(phase.name());
        row.setStatus(status.name());
        row.setErrorCode(error);
        if (!records.replace(row)) throw new DevelopmentException(CONFLICT, "操作已由另一个恢复者推进", 409);
        return require(UUID.fromString(row.getOperationId()));
    }

    public OperationView view(DevelopmentOperationEntity row) {
        var builds =
                records.builds(row.getOperationId()).stream()
                        .map(b -> UUID.fromString(b.getBuildId()))
                        .toList();
        return new OperationView(
                UUID.fromString(row.getOperationId()),
                OperationType.valueOf(row.getType()),
                Phase.valueOf(row.getPhase()),
                OperationStatus.valueOf(row.getStatus()),
                row.getVersion(),
                row.getPushedSha(),
                row.getMergeSha(),
                builds,
                row.getErrorCode() == null
                        ? null
                        : new OperationError(row.getErrorCode(), row.getErrorCode(), true));
    }

    /** 失败归属于调用时的版本；不能把另一个恢复者已推进的状态覆盖为失败。 */
    public OperationView fail(
            DevelopmentOperationEntity observed, OperationStatus status, String code) {
        observed.setStatus(status.name());
        observed.setErrorCode(code);
        records.replace(observed);
        return view(require(UUID.fromString(observed.getOperationId())));
    }

    public static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(HASH)
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean active(DevelopmentOperationEntity row) {
        return row.getStatus().equals(OperationStatus.RUNNING.name())
                || row.getStatus().equals(OperationStatus.RESULT_UNKNOWN.name());
    }
}
