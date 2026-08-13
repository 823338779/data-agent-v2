package com.datagent.agentv2.context.hierarchical.persistence.repository;

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

    private static final int PLAN_SCHEMA_VERSION = 1;
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String EXECUTION_TENANT_MISMATCH =
            "Hierarchical execution tenant does not match context";
    private static final String JOB_NOT_PERSISTED = "Hierarchical compaction job was not persisted";

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

    /** 读取固定 execution 边界上可由新 owner 恢复的非终态作业。 */
    public HierarchicalCompactionJob findRecoverable(ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalCompactionJobEntity entity =
                mapper.selectRecoverable(
                        tenantId,
                        execution.executionId(),
                        execution.executionAttempt(),
                        execution.inputUpToMessageSequence());
        return entity == null ? null : toDomain(entity);
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
                documents.canonicalizeVersionedDocument(job.planDocument(), PLAN_SCHEMA_VERSION));
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

    /** 防止当前租户调用方携带其他租户的 execution fence。 */
    private static void requireExecutionTenant(String tenantId, ContextExecutionRef execution) {
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
    }
}
