package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCompactionJobEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCompactionJobMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/** 封装可恢复 job 的幂等创建、读取和联合 fencing 状态转换。 */
@Repository
public class HierarchicalCompactionJobRepository {

    /** 新计划只允许当前完整冻结 publication 预算与 generation settings 的 schema v3。 */
    private static final int WRITABLE_PLAN_SCHEMA_VERSION = 3;

    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String EXECUTION_TENANT_MISMATCH =
            "Hierarchical execution tenant does not match context";
    private static final String JOB_NOT_PERSISTED = "Hierarchical compaction job was not persisted";
    private static final String INVALID_STALE_RESET = "Invalid hierarchical stale compaction reset";

    /** job SQL 与版本化计划文档门禁依赖。 */
    private final HierarchicalCompactionJobMapper mapper;

    private final HierarchicalDocumentCodec documents;

    public HierarchicalCompactionJobRepository(
            HierarchicalCompactionJobMapper mapper, HierarchicalDocumentCodec documents) {
        this.mapper = mapper;
        this.documents = documents;
    }

    /** 按业务标识读取当前租户作业，缺失时返回 null。 */
    public HierarchicalCompactionJob findJob(String jobId) {
        HierarchicalCompactionJobEntity entity =
                mapper.selectJob(TenantContext.requiredTenantId(), jobId);
        return entity == null ? null : toDomain(entity);
    }

    /** 读取当前 attempt 自然边界上的任意状态作业，供 takeover 冲突检查。 */
    public HierarchicalCompactionJob findBoundary(ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalCompactionJobEntity entity =
                mapper.selectBoundary(
                        tenantId,
                        execution.executionId(),
                        execution.executionAttempt(),
                        execution.inputUpToMessageSequence());
        return entity == null ? null : toDomain(entity);
    }

    /** 读取固定会话、execution、输入和 checkpoint 边界上可接管的非终态作业。 */
    public HierarchicalCompactionJob findRecoverable(
            ContextExecutionRef execution, long baseVersion, long baseGeneration) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalCompactionJobEntity entity =
                mapper.selectRecoverable(
                        tenantId,
                        execution.conversationSessionId(),
                        execution.executionId(),
                        execution.inputUpToMessageSequence(),
                        baseVersion,
                        baseGeneration);
        return entity == null ? null : toDomain(entity);
    }

    /** 查找已与给定 checkpoint 原子完成的作业，供 caller 崩溃后幂等恢复。 */
    public HierarchicalCompactionJob findCompleted(
            ContextExecutionRef execution, long checkpointVersion, long checkpointGeneration) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalCompactionJobEntity entity =
                mapper.selectCompleted(
                        tenantId,
                        execution.conversationSessionId(),
                        execution.executionId(),
                        execution.inputUpToMessageSequence(),
                        checkpointVersion,
                        checkpointGeneration);
        return entity == null ? null : toDomain(entity);
    }

    /** CAS 更新 owner、attempt 和 version；计划、候选和状态保持不变。 */
    public boolean claimRecoverable(HierarchicalCompactionJob job, ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        LocalDateTime now = LocalDateTime.now();
        return mapper.claimRecoverable(
                        tenantId,
                        job.jobId(),
                        job.version(),
                        job.baseCheckpointVersion(),
                        job.baseStrategyGeneration(),
                        execution,
                        now,
                        SYSTEM_OPERATOR)
                == 1;
    }

    /** 在原自然键记录上 CAS 替换 stale READY 的 checkpoint 基线与 schema-v3 plan，并清除旧 Cold。 */
    public boolean resetStale(
            HierarchicalCompactionJob stale,
            HierarchicalCompactionJob replacement,
            ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        requireStaleReset(stale, replacement, execution);
        String planDocument =
                documents.canonicalizeVersionedDocument(
                        replacement.planDocument(), WRITABLE_PLAN_SCHEMA_VERSION);
        LocalDateTime now = LocalDateTime.now();
        return mapper.resetStale(
                        tenantId,
                        stale.jobId(),
                        stale.version(),
                        stale.baseCheckpointVersion(),
                        stale.baseStrategyGeneration(),
                        replacement.baseCheckpointVersion(),
                        replacement.baseStrategyGeneration(),
                        planDocument,
                        execution,
                        now,
                        SYSTEM_OPERATOR)
                == 1;
    }

    /** 幂等创建 execution 边界 job，并返回唯一键竞争后的记录。 */
    public HierarchicalCompactionJob insertOrSelect(HierarchicalCompactionJob candidate) {
        String tenantId = TenantContext.requiredTenantId();
        HierarchicalCompactionJobEntity entity = toEntity(candidate, tenantId);
        mapper.insertIgnore(entity);
        HierarchicalCompactionJobEntity persisted =
                mapper.selectBoundary(
                        tenantId,
                        candidate.executionId(),
                        candidate.executionAttempt(),
                        candidate.inputUpToMessageSequence());
        if (persisted == null) {
            throw new IllegalStateException(JOB_NOT_PERSISTED);
        }
        return toDomain(persisted);
    }

    /** 以读取到的乐观锁版本和调用方 execution fence 推动作业状态。 */
    public boolean transitionJob(
            String jobId,
            HierarchicalCompactionJob.Status expected,
            HierarchicalCompactionJob.Status replacement,
            ContextExecutionRef execution,
            String failureCode) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalCompactionJobEntity current = mapper.selectJob(tenantId, jobId);
        if (current == null || !expected.name().equals(current.getStatus())) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime completedAt = isTerminal(replacement) ? now : null;
        return mapper.transitionJob(
                        tenantId,
                        jobId,
                        expected.name(),
                        replacement.name(),
                        current.getVersion(),
                        execution,
                        failureCode,
                        completedAt,
                        now,
                        SYSTEM_OPERATOR)
                == 1;
    }

    /** 在 GENERATING 状态按 execution fence 幂等写入唯一 durable Cold 候选。 */
    public boolean storeColdCandidate(
            String jobId, ContextExecutionRef execution, HierarchicalColdCandidate candidate) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        String candidateDocument = documents.writeColdCandidate(candidate);
        HierarchicalCompactionJobEntity current = mapper.selectJob(tenantId, jobId);
        if (!canStoreColdCandidate(current, execution)) {
            return false;
        }
        if (current.getCandidateColdDocument() != null) {
            return sameColdCandidate(current.getCandidateColdDocument(), candidateDocument);
        }

        // 首次写入使用 version CAS；唯一键竞争后只接受完全相同的 durable candidate。
        LocalDateTime now = LocalDateTime.now();
        mapper.storeColdCandidate(
                tenantId,
                jobId,
                current.getVersion(),
                execution,
                candidateDocument,
                now,
                SYSTEM_OPERATOR);
        HierarchicalCompactionJobEntity persisted = mapper.selectJob(tenantId, jobId);
        return canStoreColdCandidate(persisted, execution)
                && persisted.getCandidateColdDocument() != null
                && sameColdCandidate(persisted.getCandidateColdDocument(), candidateDocument);
    }

    /** 按当前租户和 job ID 恢复 durable Cold candidate；尚未生成时返回 null。 */
    public HierarchicalColdCandidate findColdCandidate(String jobId) {
        HierarchicalCompactionJobEntity job =
                mapper.selectJob(TenantContext.requiredTenantId(), jobId);
        return job == null || job.getCandidateColdDocument() == null
                ? null
                : documents.readColdCandidate(job.getCandidateColdDocument());
    }

    /** 绑定租户和审计字段，并拒绝未知计划 schema。 */
    private HierarchicalCompactionJobEntity toEntity(
            HierarchicalCompactionJob job, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        HierarchicalCompactionJobEntity entity = new HierarchicalCompactionJobEntity();
        entity.setHierarchicalCompactionJobId(job.jobId());
        entity.setTenantId(tenantId);
        entity.setConversationSessionId(job.conversationSessionId());
        entity.setExecutionId(job.executionId());
        entity.setExecutionAttempt(job.executionAttempt());
        entity.setOwnerInstanceId(job.ownerInstanceId());
        entity.setInputUpToMessageSequence(job.inputUpToMessageSequence());
        entity.setBaseCheckpointVersion(job.baseCheckpointVersion());
        entity.setBaseStrategyGeneration(job.baseStrategyGeneration());
        entity.setPlanDocument(
                documents.canonicalizeVersionedDocument(
                        job.planDocument(), WRITABLE_PLAN_SCHEMA_VERSION));
        entity.setStatus(job.status().name());
        entity.setFailureCode(job.failureCode());
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(job.version());
        return entity;
    }

    /** 恢复插件私有 job 生命周期和 fencing 字段。 */
    private static HierarchicalCompactionJob toDomain(HierarchicalCompactionJobEntity entity) {
        return new HierarchicalCompactionJob(
                entity.getHierarchicalCompactionJobId(),
                entity.getConversationSessionId(),
                entity.getExecutionId(),
                entity.getExecutionAttempt(),
                entity.getOwnerInstanceId(),
                entity.getInputUpToMessageSequence(),
                entity.getBaseCheckpointVersion(),
                entity.getBaseStrategyGeneration(),
                entity.getPlanDocument(),
                HierarchicalCompactionJob.Status.valueOf(entity.getStatus()),
                entity.getFailureCode(),
                entity.getVersion());
    }

    /** 终态转换原子写入完成时间。 */
    private static boolean isTerminal(HierarchicalCompactionJob.Status status) {
        return status == HierarchicalCompactionJob.Status.SUCCEEDED
                || status == HierarchicalCompactionJob.Status.FAILED
                || status == HierarchicalCompactionJob.Status.CANCELLED;
    }

    private static boolean canStoreColdCandidate(
            HierarchicalCompactionJobEntity job, ContextExecutionRef execution) {
        return job != null
                && HierarchicalCompactionJob.Status.GENERATING.name().equals(job.getStatus())
                && job.getConversationSessionId().equals(execution.conversationSessionId())
                && job.getExecutionId().equals(execution.executionId())
                && job.getOwnerInstanceId().equals(execution.ownerInstanceId())
                && job.getExecutionAttempt() == execution.executionAttempt()
                && job.getInputUpToMessageSequence() == execution.inputUpToMessageSequence();
    }

    private static void requireStaleReset(
            HierarchicalCompactionJob stale,
            HierarchicalCompactionJob replacement,
            ContextExecutionRef execution) {
        boolean sameFence =
                stale.conversationSessionId().equals(replacement.conversationSessionId())
                        && stale.conversationSessionId().equals(execution.conversationSessionId())
                        && stale.executionId().equals(replacement.executionId())
                        && stale.executionId().equals(execution.executionId())
                        && stale.executionAttempt() == replacement.executionAttempt()
                        && stale.executionAttempt() == execution.executionAttempt()
                        && stale.ownerInstanceId().equals(replacement.ownerInstanceId())
                        && stale.ownerInstanceId().equals(execution.ownerInstanceId())
                        && stale.inputUpToMessageSequence()
                                == replacement.inputUpToMessageSequence()
                        && stale.inputUpToMessageSequence() == execution.inputUpToMessageSequence();
        boolean forwardBase =
                replacement.baseCheckpointVersion() > stale.baseCheckpointVersion()
                        && replacement.baseStrategyGeneration() > stale.baseStrategyGeneration();
        if (!sameFence
                || !forwardBase
                || stale.status() != HierarchicalCompactionJob.Status.READY_TO_PUBLISH
                || replacement.status() != HierarchicalCompactionJob.Status.PLANNED) {
            throw new IllegalArgumentException(INVALID_STALE_RESET);
        }
    }

    private boolean sameColdCandidate(String left, String right) {
        return documents.readColdCandidate(left).equals(documents.readColdCandidate(right));
    }

    /** 防止当前租户调用方携带其他租户的 execution fence。 */
    private static void requireExecutionTenant(String tenantId, ContextExecutionRef execution) {
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
    }
}
