package com.datagent.agentv2.memory;

import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.persistence.service.memory.MemoryEntryService;
import com.datagent.agentv2.persistence.service.memory.MemoryEvidenceService;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import com.datagent.agentv2.persistence.service.memory.MemorySuppressionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 个人记忆唯一写入事务边界和一致快照入口。
 *
 * <p>所有正文、证据、整体版本与操作结果同事务提交；持锁期间不调用模型，持久化 API 只接收领域类型。
 */
@Service
@RequiredArgsConstructor
public class MemoryService {
    private final MemoryStateService states;
    private final MemoryEntryService entries;
    private final MemoryOperationService operations;
    private final MemoryEvidenceService evidenceStore;
    private final MemorySuppressionService suppressions;
    private final MemoryReviewJobService jobs;
    private final MemoryReviewLeaseService leases;
    private final MemoryWritePolicy policy;
    private final MemoryContentPolicy contentPolicy;
    private final MemoryEvidencePort evidencePort;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final MemoryMetrics metrics;

    /** 原子初始化仅创建缺失状态行，绝不重置用户已有的能力设置。 */
    @Transactional
    public void initialize() {
        states.lockCurrent();
    }

    /** 使用同一状态锁排除并发写入，避免 READ COMMITTED 的两次查询读取到不同 revision。 */
    @Transactional
    public MemorySnapshot snapshot() {
        var state = states.lockCurrent();
        var current = entries.listCurrent();
        var snapshot = new MemorySnapshot(state, current, quotas(current));
        metrics.afterCommit(() -> metrics.recordQuota(snapshot.quotas()));
        return snapshot;
    }

    /** 状态锁内完成设置 CAS 和历史结果落库；重放始终返回原提交快照，不借用当前设置。 */
    @Transactional
    public MemoryState updateSettings(String requestId, MemorySettingsChange change) {
        if (requestId == null || requestId.isBlank() || change == null) {
            throw new MemoryException(MemoryStatus.INVALID_REQUEST, "设置请求无效");
        }
        var state = states.lockCurrent();
        String hash = sha256(json.writeValueAsBytes(new SettingsRequest("settings", change)));
        var previous = operations.find(requestId);
        if (previous != null) {
            if (!previous.payloadHash().equals(hash) || previous.settingsResult() == null) {
                throw new MemoryException(MemoryStatus.CONFLICT, "操作键已用于其他请求");
            }
            return previous.settingsResult();
        }
        if (change.expectedVersion() != state.settingsVersion()) {
            throw new MemoryException(MemoryStatus.CONFLICT, "设置版本已变化");
        }
        var now = states.currentBusinessTime();
        var changed = state.changed(change, now);
        // 设置入口独立于写入开关，避免用户关闭显式写入后无法重新开启。
        if (!changed.equals(state) && !states.updateSettings(changed, state.settingsVersion())) {
            throw new IllegalStateException("记忆设置在状态锁内发生意外版本变化");
        }
        operations.insert(
                new MemoryOperation(
                        requestId,
                        hash,
                        MemoryOutcome.composeFromEntry(
                                MemoryStatus.UPDATED,
                                null,
                                changed.revision(),
                                quotas(entries.listCurrent())),
                        now,
                        changed));
        return changed;
    }

    private record SettingsRequest(String action, MemorySettingsChange change) {}

    @Transactional
    public MemoryOutcome execute(MemoryWriteContext context, MemoryCommand command) {
        var state = states.lockCurrent();
        String payloadHash = payloadHash(context, command);
        var previous = operations.find(context.requestId());
        if (previous != null) {
            if (!previous.payloadHash().equals(payloadHash)) {
                return MemoryOutcome.composeFromEntry(
                        MemoryStatus.CONFLICT,
                        null,
                        state.revision(),
                        quotas(entries.listCurrent()));
            }
            var result = previous.outcome();
            return result.withTargetExists(
                    result.memoryId() != null && entries.find(result.memoryId()) != null);
        }

        var current = entries.listCurrent();
        MemoryEntry target = null;
        MemoryOutcome result;
        try {
            // 必须在当前事务依次锁 state → job → lease，然后以数据库真实时刻重新验证双方。
            // 工具执行前的资格检查不保护提交窗口，旧 worker 否则可能在被接管后写回。
            validateBackground(context, state);
            String targetId =
                    switch (command) {
                        case MemoryCommand.Add ignored -> null;
                        case MemoryCommand.Replace replace -> replace.memoryId();
                        case MemoryCommand.Remove remove -> remove.memoryId();
                        case MemoryCommand.Confirm confirm -> confirm.memoryId();
                    };
            target = targetId == null ? null : entries.find(targetId);
            policy.authorize(context, command, target, state);
            var evidence =
                    context.caller() == MemoryWriteContext.Caller.UI
                            ? List.<MemoryEvidence>of()
                            : evidencePort.resolve(context, command.evidenceMessageIds());
            policy.validateEvidence(context, command, evidence);
            // 证据查询可能等待其他事务；持有行锁只能阻止接管，不能阻止真实时间越过租约期限。
            validateClaim(context);
            result = write(context, command, target, state, current, evidence);
        } catch (MemoryException exception) {
            // 可预期拒绝与请求一并提交，避免适配器抛异常导致幂等记录回滚。依赖故障仍整体回滚以便重试。
            if (exception.code() == MemoryStatus.UNAVAILABLE) {
                throw exception;
            }
            result =
                    MemoryOutcome.composeFromEntry(
                            exception.code(), target, state.revision(), quotas(current));
        }
        operations.insert(
                new MemoryOperation(
                        context.requestId(), payloadHash, result, states.currentBusinessTime()));
        var committed = result;
        metrics.afterCommit(
                () -> {
                    metrics.recordOperation(
                            source(context, MemoryWritePolicy.explicit(context, command)),
                            committed.status());
                    metrics.recordQuota(committed.quotas());
                });
        return result;
    }

    private MemoryOutcome write(
            MemoryWriteContext context,
            MemoryCommand command,
            MemoryEntry target,
            MemoryState state,
            List<MemoryEntry> current,
            List<MemoryEvidence> evidence) {
        if (command instanceof MemoryCommand.Remove) {
            return remove(context, target, state, current);
        }
        if (command instanceof MemoryCommand.Confirm) {
            return confirm(context, target, state, current, evidence);
        }
        MemoryKind kind;
        String content;
        if (command instanceof MemoryCommand.Add add) {
            kind = add.kind();
            content = add.content();
        } else if (command instanceof MemoryCommand.Replace replace) {
            kind = target.kind();
            content = replace.newContent();
        } else {
            throw new MemoryException(MemoryStatus.INVALID_REQUEST, "当前命令不支持此写入操作");
        }
        contentPolicy.validateContent(content);
        String hash = contentPolicy.hash(content);
        // 恢复必须经过全部验证和最终预算后才撤销抑制；旧快照已由 authorize 拒绝。
        var suppression = suppressions.find(hash);
        if (suppression != null) {
            policy.validateRestore(context, command, suppression, evidence);
        }
        var duplicate =
                current.stream()
                        .filter(item -> item.kind() == kind && item.normalizedHash().equals(hash))
                        .findFirst()
                        .orElse(null);
        boolean explicit = MemoryWritePolicy.explicit(context, command);
        if (duplicate != null
                && target != null
                && !duplicate.memoryId().equals(target.memoryId())) {
            throw new MemoryException(MemoryStatus.DUPLICATE_CONFLICT, "目标正文与其他条目重复");
        }
        if (duplicate != null && target == null) {
            if (!explicit) {
                return MemoryOutcome.composeFromEntry(
                        MemoryStatus.ALREADY_EXISTS, duplicate, state.revision(), quotas(current));
            }
            // 明确重复属于人工确认：保留原文/来源且提升保护；输入即使超长也不增加库存。
            // 即使携带 restore，也没有新建已删除条目，不撤销另一分类删除产生的抑制。
            var confirmed =
                    duplicate.revised(
                            duplicate.content(),
                            duplicate.normalizedHash(),
                            true,
                            states.currentBusinessTime());
            persistRevision(confirmed, duplicate, state);
            appendEvidence(context, confirmed, evidence);
            return MemoryOutcome.composeFromEntry(
                    MemoryStatus.ALREADY_EXISTS, confirmed, state.revision() + 1, quotas(current));
        }

        long used =
                current.stream()
                        .filter(item -> item.kind() == kind)
                        .mapToLong(item -> item.content().length())
                        .sum();
        long finalUsed = used - (target == null ? 0 : target.content().length()) + content.length();
        if (finalUsed > contentPolicy.limit(kind)) {
            throw new MemoryException(MemoryStatus.QUOTA_EXCEEDED, "记忆超过分类容量限制");
        }
        if (suppression != null) {
            suppressions.deleteByHash(hash);
        }
        var now = states.currentBusinessTime();
        var changed =
                target == null
                        ? MemoryEntry.composeFromNewMemory(
                                ids.next("memory"),
                                kind,
                                content,
                                hash,
                                source(context, explicit),
                                explicit,
                                now,
                                context.caller() == MemoryWriteContext.Caller.UI
                                        ? null
                                        : context.promptVersion())
                        : target.revised(content, hash, explicit, now);
        persistRevision(changed, target, state);
        appendEvidence(context, changed, evidence);
        var finalEntries = new ArrayList<>(current);
        if (target != null) {
            finalEntries.remove(target);
        }
        finalEntries.add(changed);
        return MemoryOutcome.composeFromEntry(
                target == null ? MemoryStatus.CREATED : MemoryStatus.UPDATED,
                changed,
                state.revision() + 1,
                quotas(finalEntries));
    }

    /** 物理删除与跨分类抑制同事务提交；CAS 失败属于边界破坏，必须整体回滚。 */
    private MemoryOutcome remove(
            MemoryWriteContext context,
            MemoryEntry target,
            MemoryState state,
            List<MemoryEntry> current) {
        evidenceStore.deleteByMemoryId(target.memoryId());
        if (!entries.deletePhysical(target.memoryId(), target.version())) {
            throw new IllegalStateException("记忆删除在状态锁内发生意外版本变化");
        }
        // 同指纹可能已存在另一分类的删除事件，以最新删除时刻约束此后的恢复证据。
        suppressions.deleteByHash(target.normalizedHash());
        suppressions.insert(
                new MemorySuppression(
                        target.normalizationVersion(),
                        target.normalizedHash(),
                        state.revision() + 1,
                        states.currentBusinessTime(),
                        context.requestId()));
        if (!states.advanceRevision(state.revision())) {
            throw new IllegalStateException("记忆状态在持锁期间发生意外版本变化");
        }
        var remaining = new ArrayList<>(current);
        remaining.remove(target);
        return MemoryOutcome.composeFromEntry(
                        MemoryStatus.REMOVED, target, state.revision() + 1, quotas(remaining))
                .withTargetExists(false);
    }

    /** 同证据的重复确认没有状态变化；只读和自动重复不能延长事实的确认寿命。 */
    private MemoryOutcome confirm(
            MemoryWriteContext context,
            MemoryEntry target,
            MemoryState state,
            List<MemoryEntry> current,
            List<MemoryEvidence> evidence) {
        boolean fresh = false;
        for (var item : evidence) {
            fresh |= evidenceStore.append(target.memoryId(), item, context.promptVersion());
        }
        if (!fresh) {
            return MemoryOutcome.composeFromEntry(
                    MemoryStatus.CONFIRMED, target, state.revision(), quotas(current));
        }
        var changed =
                target.revised(
                        target.content(),
                        target.normalizedHash(),
                        false,
                        states.currentBusinessTime());
        persistRevision(changed, target, state);
        return MemoryOutcome.composeFromEntry(
                MemoryStatus.CONFIRMED, changed, state.revision() + 1, quotas(current));
    }

    private void persistRevision(MemoryEntry changed, MemoryEntry original, MemoryState state) {
        if (original == null) {
            entries.insert(changed);
        } else if (!entries.replace(changed, original.version())) {
            // 持有状态锁后 CAS 仍失败说明事务边界被绕过；不能把部分修改包装成可提交拒绝。
            throw new IllegalStateException("记忆条目在状态锁内发生意外版本变化");
        }
        if (!states.advanceRevision(state.revision())) {
            throw new IllegalStateException("记忆状态在持锁期间发生意外版本变化");
        }
    }

    private void appendEvidence(
            MemoryWriteContext context, MemoryEntry target, List<MemoryEvidence> evidence) {
        if (context.caller() == MemoryWriteContext.Caller.UI) {
            // 请求键最长 256 字符，证据 ID 最长 128；使用稳定操作键摘要关联同事务提交的 UI 事件。
            var event =
                    new MemoryEvidence(
                            "ui_" + sha256(context.requestId().getBytes(StandardCharsets.UTF_8)),
                            MemoryEvidence.Kind.UI_OPERATION,
                            null,
                            null,
                            0,
                            OperatorContext.requiredUserId(),
                            0,
                            "",
                            states.currentBusinessTime());
            evidenceStore.append(target.memoryId(), event);
        } else {
            evidence.forEach(
                    item -> evidenceStore.append(target.memoryId(), item, context.promptVersion()));
        }
    }

    private void validateBackground(MemoryWriteContext context, MemoryState state) {
        if (context.caller() != MemoryWriteContext.Caller.BACKGROUND) {
            return;
        }
        var claim = context.reviewClaim();
        var job = jobs.lock(claim.jobId());
        var lease = leases.lockCurrent();
        if (job == null
                || lease == null
                || job.status() != MemoryReviewJob.Status.RUNNING
                || claim.epoch() != state.learningEpoch()
                || !Objects.equals(context.conversationId(), claim.conversationId())) {
            throw new MemoryException(MemoryStatus.LEASE_LOST, "后台写入资格已失效");
        }
        validateClaim(context);
    }

    private void validateClaim(MemoryWriteContext context) {
        if (context.caller() != MemoryWriteContext.Caller.BACKGROUND) {
            return;
        }
        boolean validJob = jobs.validateClaim(context.reviewClaim());
        boolean validLease = leases.validateClaim(context.reviewClaim());
        if (!validJob || !validLease) {
            throw new MemoryException(MemoryStatus.LEASE_LOST, "后台写入资格已失效");
        }
    }

    private Map<MemoryKind, MemoryQuota> quotas(List<MemoryEntry> current) {
        var result = new EnumMap<MemoryKind, MemoryQuota>(MemoryKind.class);
        for (var kind : MemoryKind.values()) {
            int used =
                    current.stream()
                            .filter(item -> item.kind() == kind)
                            .mapToInt(item -> item.content().length())
                            .sum();
            result.put(kind, new MemoryQuota(used, contentPolicy.limit(kind)));
        }
        return result;
    }

    private static MemorySource source(MemoryWriteContext context, boolean explicit) {
        return switch (context.caller()) {
            case UI -> MemorySource.USER_UI;
            case MAIN -> explicit ? MemorySource.USER_DIRECT : MemorySource.LLM_ACTIVE;
            case BACKGROUND -> MemorySource.BACKGROUND_REVIEW;
        };
    }

    private String payloadHash(MemoryWriteContext context, MemoryCommand command) {
        var claim = context.reviewClaim();
        var batch =
                claim == null
                        ? null
                        : new EvidenceBatch(
                                claim.jobId(),
                                claim.epoch(),
                                claim.claimedGeneration(),
                                claim.fromSequence(),
                                claim.throughSequence());
        // 冻结 revision 是并发前置条件，重试时可推进；owner/token/lease 也会变化，均不属于请求语义。
        // 命令本身含 reason、原始正文、证据 ID、restore、目标及 expectedVersion，动作单独区分。
        return sha256(
                json.writeValueAsBytes(
                        new SemanticRequest(
                                context.caller(),
                                context.executionId(),
                                context.conversationId(),
                                command.getClass().getSimpleName(),
                                command,
                                batch)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /** 稳定请求语义只参与摘要计算，从不把记录中的 command 正文写入操作表。 */
    private record SemanticRequest(
            MemoryWriteContext.Caller caller,
            String executionId,
            String conversationId,
            String action,
            MemoryCommand command,
            EvidenceBatch batch) {}

    /** 后台冻结证据批次；租约续期与重新领取不改变该批次的事实来源。 */
    private record EvidenceBatch(
            String jobId, long epoch, long generation, long fromSequence, long throughSequence) {}
}
