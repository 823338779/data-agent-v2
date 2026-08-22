package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmCompactionJobMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 封装可恢复 LSM 压缩作业的插件私有查询。 */
@Repository
@RequiredArgsConstructor
public class LsmCompactionJobRepository {

    private static final String SYSTEM_OPERATOR = "lsm-context";

    private final LsmCompactionJobMapper mapper;

    /** 读取当前租户 execution 边界上仍可恢复的作业。 */
    public Optional<LsmCompactionJobEntity> findRecoverableJob(
            String executionId, long executionAttempt, long inputBoundary) {
        return Optional.ofNullable(
                mapper.selectRecoverableJob(
                        TenantContext.requiredTenantId(),
                        executionId,
                        executionAttempt,
                        inputBoundary));
    }

    /** 读取 execution/attempt/input 唯一边界上的任意状态作业。 */
    public Optional<LsmCompactionJobEntity> findBoundaryJob(
            String executionId, long executionAttempt, long inputBoundary) {
        return Optional.ofNullable(
                mapper.selectBoundaryJob(
                        TenantContext.requiredTenantId(),
                        executionId,
                        executionAttempt,
                        inputBoundary));
    }

    /** 幂等写入作业后读回唯一边界记录。 */
    public LsmCompactionJobEntity findOrCreate(LsmCompactionJobEntity candidate) {
        candidate.setTenantId(TenantContext.requiredTenantId());
        mapper.insertIgnore(candidate);
        return findBoundaryJob(
                        candidate.getExecutionId(),
                        candidate.getExecutionAttempt(),
                        candidate.getInputUpToMessageSequence())
                .orElseThrow(
                        () -> new IllegalStateException("LSM compaction job was not persisted"));
    }

    /** 由已通过 execution fencing 的 owner 接管可恢复作业。 */
    public boolean claim(LsmCompactionJobEntity job, String ownerInstanceId) {
        return mapper.claimRecoverable(
                        TenantContext.requiredTenantId(),
                        job.getCompactionJobId(),
                        job.getExecutionAttempt(),
                        job.getInputUpToMessageSequence(),
                        job.getVersion(),
                        ownerInstanceId,
                        LocalDateTime.now(),
                        SYSTEM_OPERATOR)
                == 1;
    }

    /** 通过 owner/attempt/input 条件把作业推进到下一状态。 */
    public boolean transition(
            LsmCompactionJobEntity job,
            String ownerInstanceId,
            String sourceStatuses,
            String targetStatus,
            String failureCode,
            boolean terminal) {
        LocalDateTime now = LocalDateTime.now();
        return mapper.transitionStatus(
                        TenantContext.requiredTenantId(),
                        job.getCompactionJobId(),
                        ownerInstanceId,
                        job.getExecutionAttempt(),
                        job.getInputUpToMessageSequence(),
                        sourceStatuses,
                        targetStatus,
                        failureCode,
                        terminal ? now : null,
                        now,
                        SYSTEM_OPERATOR)
                == 1;
    }

    /** 在唯一 execution 边界上把 SUPERSEDED job 重置为基于新 source manifest 的 PLANNED。 */
    public boolean replanSuperseded(
            LsmCompactionJobEntity superseded, LsmCompactionJobEntity next) {
        return mapper.replanSuperseded(
                        TenantContext.requiredTenantId(),
                        superseded.getCompactionJobId(),
                        superseded.getExecutionAttempt(),
                        superseded.getInputUpToMessageSequence(),
                        next.getSourceManifestGeneration(),
                        next.getSourceManifestVersion(),
                        next.getPolicySnapshot(),
                        next.getPlanDocument(),
                        next.getPlanSha256(),
                        next.getOwnerInstanceId(),
                        LocalDateTime.now(),
                        SYSTEM_OPERATOR)
                == 1;
    }
}
