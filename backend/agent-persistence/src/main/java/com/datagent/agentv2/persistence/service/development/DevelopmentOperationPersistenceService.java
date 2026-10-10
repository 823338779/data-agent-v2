package com.datagent.agentv2.persistence.service.development;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.development.DevelopmentBuildAttemptEntity;
import com.datagent.agentv2.persistence.entity.development.DevelopmentOperationEntity;
import com.datagent.agentv2.persistence.entity.development.DevelopmentRecoveryEntity;
import com.datagent.agentv2.persistence.mapper.development.DevelopmentBuildAttemptMapper;
import com.datagent.agentv2.persistence.mapper.development.DevelopmentOperationMapper;
import com.datagent.agentv2.persistence.mapper.development.DevelopmentRecoveryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 操作幂等、阶段 CAS 和构建尝试；每个方法只有短数据库事务。 */
@Service
@RequiredArgsConstructor
public class DevelopmentOperationPersistenceService
        extends ServiceImpl<DevelopmentOperationMapper, DevelopmentOperationEntity> {
    private static final String FOR_UPDATE = "FOR UPDATE";
    private static final String PENDING_ORDER =
            "ORDER BY last_checked_at NULLS FIRST, id LIMIT 50 FOR UPDATE SKIP LOCKED";
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String RESULT_UNKNOWN = "RESULT_UNKNOWN";
    private static final String FAILED = "FAILED";
    private static final String SUCCEEDED = "SUCCEEDED";
    private final DevelopmentBuildAttemptMapper attempts;
    private final DevelopmentRecoveryMapper recoveries;
    private final com.datagent.agentv2.persistence.mapper.development.DevelopmentProjectMapper
            projects;

    /** 与配置和项目停用串行化受理，Git确认后的配置变化只能拒绝、不能静默换版本。 */
    @Transactional
    public DevelopmentOperationEntity acceptConfigured(
            DevelopmentOperationEntity next, long configVersion) {
        var project =
                projects.selectOne(
                        Wrappers
                                .<com.datagent.agentv2.persistence.entity.development
                                                .DevelopmentProjectEntity>
                                        lambdaQuery()
                                .eq(
                                        com.datagent.agentv2.persistence.entity.development
                                                        .DevelopmentProjectEntity
                                                ::getProjectId,
                                        next.getProjectId())
                                .last(FOR_UPDATE));
        if (project == null
                || !Boolean.TRUE.equals(project.getEnabled())
                || project.getDiagnostic() != null
                || project.getVersion() != configVersion)
            throw new IllegalStateException("开发配置已经变化或项目已停用");
        return accept(next);
    }

    /** 锁定原动作后记回执与推进状态；同键重放不产生第二次构建尝试。 */
    @Transactional
    public DevelopmentOperationEntity recover(
            String operationId, long expectedVersion, String key, String submissionPhase) {
        var row =
                lambdaQuery()
                        .eq(DevelopmentOperationEntity::getOperationId, operationId)
                        .last(FOR_UPDATE)
                        .one();
        if (row == null) throw new IllegalStateException("操作不存在");
        baseMapper.lockScope(row.getSessionId());
        var prior =
                recoveries.selectOne(
                        Wrappers.<DevelopmentRecoveryEntity>lambdaQuery()
                                .eq(DevelopmentRecoveryEntity::getOperationId, operationId)
                                .eq(DevelopmentRecoveryEntity::getIdempotencyKey, key));
        if (prior != null) {
            if (prior.getExpectedVersion() != expectedVersion)
                throw new IllegalStateException("恢复键不能改变输入");
            return row;
        }
        if (row.getVersion() != expectedVersion) throw new IllegalStateException("操作版本已变化");
        if (lambdaQuery()
                .eq(DevelopmentOperationEntity::getSessionId, row.getSessionId())
                .ne(DevelopmentOperationEntity::getOperationId, operationId)
                .in(DevelopmentOperationEntity::getStatus, PENDING, RUNNING, RESULT_UNKNOWN)
                .exists()) throw new IllegalStateException("本会话已有另一个开发动作");
        var receipt = new DevelopmentRecoveryEntity();
        receipt.setOperationId(operationId);
        receipt.setIdempotencyKey(key);
        receipt.setExpectedVersion(expectedVersion);
        recoveries.insert(receipt);
        if (!SUCCEEDED.equals(row.getStatus())) {
            // 只有已知失败的构建才能增加尝试；未知受理结果必须重放原键。
            if (FAILED.equals(row.getStatus()) && row.getBuildId() != null) {
                row.setAttemptNumber(row.getAttemptNumber() + 1);
                row.setBuildId(null);
                row.setPhase(submissionPhase);
            }
            row.setStatus(RUNNING);
            row.setErrorCode(null);
            if (!replace(row)) throw new IllegalStateException("操作版本已变化");
        }
        return find(operationId);
    }

    public DevelopmentOperationEntity find(String operationId) {
        return lambdaQuery().eq(DevelopmentOperationEntity::getOperationId, operationId).one();
    }

    public DevelopmentOperationEntity byKey(String sessionId, String type, String key) {
        return lambdaQuery()
                .eq(DevelopmentOperationEntity::getSessionId, sessionId)
                .eq(DevelopmentOperationEntity::getType, type)
                .eq(DevelopmentOperationEntity::getIdempotencyKey, key)
                .one();
    }

    @Transactional
    public DevelopmentOperationEntity accept(DevelopmentOperationEntity next) {
        baseMapper.lockScope(next.getSessionId());
        var prior = byKey(next.getSessionId(), next.getType(), next.getIdempotencyKey());
        if (prior != null) {
            if (!Objects.equals(prior.getRequestDigest(), next.getRequestDigest())) {
                throw new IllegalStateException("同一操作键不能改变输入");
            }
            return prior;
        }
        if (lambdaQuery()
                .eq(DevelopmentOperationEntity::getSessionId, next.getSessionId())
                .in(DevelopmentOperationEntity::getStatus, PENDING, RUNNING, RESULT_UNKNOWN)
                .exists()) {
            throw new IllegalStateException("本会话已有未完成的开发动作");
        }
        save(next);
        return find(next.getOperationId());
    }

    public boolean replace(DevelopmentOperationEntity next) {
        return lambdaUpdate()
                .eq(DevelopmentOperationEntity::getOperationId, next.getOperationId())
                .eq(DevelopmentOperationEntity::getVersion, next.getVersion())
                .set(DevelopmentOperationEntity::getPhase, next.getPhase())
                .set(DevelopmentOperationEntity::getStatus, next.getStatus())
                .set(DevelopmentOperationEntity::getCandidateSha, next.getCandidateSha())
                .set(
                        DevelopmentOperationEntity::getCandidateParentSha,
                        next.getCandidateParentSha())
                .set(
                        DevelopmentOperationEntity::getCandidateStoragePath,
                        next.getCandidateStoragePath())
                .set(DevelopmentOperationEntity::getPushedSha, next.getPushedSha())
                .set(DevelopmentOperationEntity::getMergeSha, next.getMergeSha())
                .set(DevelopmentOperationEntity::getBuildId, next.getBuildId())
                .set(DevelopmentOperationEntity::getAttemptNumber, next.getAttemptNumber())
                .set(DevelopmentOperationEntity::getErrorCode, next.getErrorCode())
                .set(DevelopmentOperationEntity::getVersion, next.getVersion() + 1)
                .update();
    }

    /** 记录一次受理的构建与操作指针必须一起提交；重放不会重复创建尝试。 */
    @Transactional
    public boolean recordBuild(
            DevelopmentOperationEntity next, DevelopmentBuildAttemptEntity attempt) {
        var locked =
                lambdaQuery()
                        .eq(DevelopmentOperationEntity::getOperationId, next.getOperationId())
                        .last(FOR_UPDATE)
                        .one();
        if (locked.getVersion().longValue() != next.getVersion().longValue()) return false;
        var prior =
                attempts.selectOne(
                        Wrappers.<DevelopmentBuildAttemptEntity>lambdaQuery()
                                .eq(
                                        DevelopmentBuildAttemptEntity::getOperationId,
                                        attempt.getOperationId())
                                .eq(
                                        DevelopmentBuildAttemptEntity::getAttemptNumber,
                                        attempt.getAttemptNumber()));
        if (prior == null) attempts.insert(attempt);
        else if (!Objects.equals(prior.getBuildId(), attempt.getBuildId())) {
            throw new IllegalStateException("构建尝试身份已固定");
        }
        return replace(next);
    }

    public List<DevelopmentBuildAttemptEntity> builds(String operationId) {
        return attempts.selectList(
                Wrappers.<DevelopmentBuildAttemptEntity>lambdaQuery()
                        .eq(DevelopmentBuildAttemptEntity::getOperationId, operationId)
                        .orderByAsc(DevelopmentBuildAttemptEntity::getAttemptNumber));
    }

    @Transactional
    public List<DevelopmentOperationEntity> pending() {
        // 内部扫描时间不改变操作版本；避免前一批未知结果永远饿死新操作。
        var rows =
                lambdaQuery()
                        .in(DevelopmentOperationEntity::getStatus, PENDING, RUNNING, RESULT_UNKNOWN)
                        .last(PENDING_ORDER)
                        .list();
        for (var row : rows) {
            lambdaUpdate()
                    .eq(DevelopmentOperationEntity::getId, row.getId())
                    .set(DevelopmentOperationEntity::getLastCheckedAt, LocalDateTime.now())
                    .update();
        }
        return rows;
    }

    public List<DevelopmentOperationEntity> forSession(String sessionId) {
        return lambdaQuery()
                .eq(DevelopmentOperationEntity::getSessionId, sessionId)
                .orderByDesc(DevelopmentOperationEntity::getId)
                .list();
    }
}
