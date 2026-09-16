package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.*;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.tool.MemoryToolDispatcher;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 有界私有审查循环；模型 I/O 不持事务，提交与心跳由独立短事务再次检查数据库 fencing。 */
@RequiredArgsConstructor
public final class MemoryReviewWorker {
    private final MemoryProperties properties;
    private final MemoryReviewCoordinator coordinator;
    private final MemoryService memory;
    private final MemoryReviewHistoryPort history;
    private final MemoryReviewModelPort model;
    private final MemoryToolDispatcher dispatcher;
    private final MemoryOperationService operations;
    private final MemoryContentPolicy content;
    private final ObjectMapper json;
    private final ScheduledExecutorService guards;

    /** 配置只持久化摘要；模型未配置时也可计算，且重启相同配置不会改变摘要。 */
    public String configurationFingerprint() {
        return digest(
                json.writeValueAsString(
                        List.of(
                                model.configurationFingerprint(),
                                properties.getReviewMessageLimit(),
                                properties.getReviewCharLimit(),
                                properties.getMaxModelCalls(),
                                properties.getMaxMaintenanceCalls(),
                                properties.getReviewDeadline().toString(),
                                properties.getLeaseDuration().toString(),
                                properties.getPersonProfileLimit(),
                                properties.getCollaborationPreferenceLimit(),
                                MemoryReviewPrompts.VERSION)));
    }

    public void run(MemoryReviewClaim claim) {
        String fingerprint = configurationFingerprint();
        var stopped = new AtomicBoolean();
        Thread thread = Thread.currentThread();
        long deadline = System.nanoTime() + properties.getReviewDeadline().toNanos();
        var timeout =
                guards.schedule(
                        () -> stop(claim, stopped, thread, "DEADLINE", fingerprint),
                        properties.getReviewDeadline().toNanos(),
                        TimeUnit.NANOSECONDS);
        long heartbeatNanos = Math.max(1, properties.getLeaseDuration().toNanos() / 3);
        var heartbeat =
                guards.scheduleWithFixedDelay(
                        () -> {
                            if (stopped.get()) return;
                            try {
                                if (!coordinator.renew(claim))
                                    stop(claim, stopped, thread, "LEASE_LOST", fingerprint);
                            } catch (RuntimeException failure) {
                                stop(claim, stopped, thread, "HEARTBEAT_FAILED", fingerprint);
                            }
                        },
                        heartbeatNanos,
                        heartbeatNanos,
                        TimeUnit.NANOSECONDS);
        try {
            var snapshot = memory.snapshot();
            int budget = model.maxInputTokens();
            var protocol = new ArrayList<Map<String, Object>>();
            String jobPrefix = jobPrefix(claim);
            var previous =
                    operations.findReviewResults(jobPrefix, properties.getMaxMaintenanceCalls());
            if (!previous.isEmpty())
                protocol.add(
                        Map.of(
                                "type",
                                "historical",
                                "results",
                                previous.stream().map(MemoryOperation::outcome).toList()));
            var initial = snapshot;
            var slice =
                    new MemoryReviewSliceSelector(properties)
                            .select(
                                    history.read(
                                            claim,
                                            claim.fromSequence(),
                                            properties.getReviewMessageLimit() + 1),
                                    claim.fromSequence(),
                                    claim.throughSequence(),
                                    rows ->
                                            model.estimate(
                                                    new MemoryReviewInput(
                                                            initial, rows, protocol, budget)),
                                    budget);
            if (slice.blocked()) throw new ReviewFailure("INPUT_TOO_LARGE", true);
            if (slice.messages().isEmpty()) {
                check(stopped, deadline);
                if (slice.reviewedThroughSequence() == claim.fromSequence())
                    throw new ReviewFailure("PENDING_MESSAGE", false);
                coordinator.finish(
                        claim, new MemoryReviewProgress(slice.reviewedThroughSequence(), 0));
                return;
            }
            long revision = snapshot.state().revision();
            int maintenance = 0;
            int rejected = 0;
            var handled = new HashMap<String, MemoryOutcome>();
            for (int call = 0; call < properties.getMaxModelCalls(); call++) {
                check(stopped, deadline);
                var input = new MemoryReviewInput(snapshot, slice.messages(), protocol, budget);
                if (model.estimate(input) > budget)
                    throw new ReviewFailure("INPUT_TOO_LARGE", true);
                var output = model.complete(input);
                check(stopped, deadline);
                if (output instanceof MemoryReviewOutput.Done) {
                    // finish 的返回状态可能为 PENDING/CANCELLED；此处不发布“完成”事件或把取消计为成功。
                    coordinator.finish(
                            claim,
                            new MemoryReviewProgress(slice.reviewedThroughSequence(), rejected));
                    return;
                }
                var calls = ((MemoryReviewOutput.Calls) output).calls();
                protocol.add(Map.of("type", "calls", "calls", calls));
                for (var item : calls) {
                    check(stopped, deadline);
                    if (++maintenance > properties.getMaxMaintenanceCalls())
                        throw new ReviewFailure("MAINTENANCE_LIMIT", false);
                    var context = context(claim, "validating", revision);
                    var arguments = json.valueToTree(item.arguments());
                    var command = dispatcher.parse(context, arguments);
                    var evidence = validateEvidence(command, slice.messages());
                    String stable = stablePrefix(jobPrefix, command, evidence);
                    // 完整参数已通过统一解析器；历史成功不再执行，也不能把旧结果 revision 当成本轮新提交。
                    var committed = operations.findSuccessfulReviewAction(stable);
                    MemoryOutcome outcome;
                    boolean historical = committed != null || handled.containsKey(stable);
                    if (committed != null) {
                        var old = committed.outcome();
                        outcome =
                                old.withTargetExists(
                                        snapshot.entries().stream()
                                                .anyMatch(
                                                        entry ->
                                                                entry.memoryId()
                                                                        .equals(old.memoryId())));
                    } else if (handled.containsKey(stable)) outcome = handled.get(stable);
                    else {
                        String request =
                                stable
                                        + digest(
                                                json.writeValueAsString(
                                                        List.of(
                                                                command,
                                                                revision,
                                                                claim.claimedGeneration(),
                                                                claim.fromSequence(),
                                                                claim.throughSequence())));
                        check(stopped, deadline);
                        outcome = dispatcher.dispatch(context(claim, request, revision), arguments);
                        handled.put(stable, outcome);
                        if (outcome.successful()) revision = outcome.revision();
                    }
                    protocol.add(
                            Map.of(
                                    "type",
                                    "result",
                                    "callId",
                                    item.providerCallId(),
                                    "historical",
                                    historical,
                                    "outcome",
                                    outcome));
                    if (!outcome.successful()) {
                        if (!definitive(outcome.status()))
                            throw new ReviewFailure(outcome.status().name(), false);
                        rejected++;
                    }
                }
                // 后续模型调用需要看到自己的成功更新；外部变化不能偷偷替换冻结 revision，必须停止并重新领取。
                var refreshed = memory.snapshot();
                if (refreshed.state().revision() != revision)
                    throw new ReviewFailure("CONFLICT", false);
                snapshot = refreshed;
            }
            throw new ReviewFailure("MODEL_CALL_LIMIT", false);
        } catch (MemoryReviewConfigurationException failure) {
            if (!stopped.get()) coordinator.fail(claim, "MODEL_CONFIGURATION", true, fingerprint);
        } catch (ReviewFailure failure) {
            if (!stopped.get())
                coordinator.fail(claim, failure.reason, failure.blocked, fingerprint);
        } catch (MemoryException failure) {
            if (!stopped.get()) coordinator.fail(claim, failure.code().name(), false, fingerprint);
        } catch (IllegalArgumentException failure) {
            if (!stopped.get()) coordinator.fail(claim, "INVALID_REQUEST", false, fingerprint);
        } catch (RuntimeException failure) {
            if (!stopped.get()) coordinator.fail(claim, "MODEL_FAILURE", false, fingerprint);
        } finally {
            boolean guardStopped;
            synchronized (stopped) {
                guardStopped = stopped.getAndSet(true);
            }
            timeout.cancel(false);
            heartbeat.cancel(false);
            // 守卫已接管失败收敛时不能抢先 release 成 PENDING，否则会吞掉失败计数；数据库故障交给 TTL 接管。
            if (!guardStopped) coordinator.release(claim);
            // 本线程由后台池复用，已处理的中断不能泄漏给下一任务。
            Thread.interrupted();
        }
    }

    private void stop(
            MemoryReviewClaim claim,
            AtomicBoolean stopped,
            Thread thread,
            String reason,
            String fingerprint) {
        synchronized (stopped) {
            if (!stopped.compareAndSet(false, true)) return;
            // 中断必须在数据库 I/O 前且与 finally 互斥；否则慢 fail 返回时可能误中断该池线程的下一任务。
            thread.interrupt();
        }
        coordinator.fail(claim, reason, false, fingerprint);
    }

    private static void check(AtomicBoolean stopped, long deadline) {
        if (stopped.get() || Thread.currentThread().isInterrupted())
            throw new ReviewFailure("LEASE_LOST", false);
        if (System.nanoTime() >= deadline) throw new ReviewFailure("DEADLINE", false);
    }

    private static MemoryWriteContext context(
            MemoryReviewClaim claim, String requestId, long revision) {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.BACKGROUND,
                requestId,
                null,
                claim.conversationId(),
                revision,
                claim,
                MemoryReviewPrompts.VERSION);
    }

    private static List<MemoryEvidence> validateEvidence(
            MemoryCommand command, List<MemoryEvidence> rows) {
        var result =
                command.evidenceMessageIds().stream()
                        .sorted()
                        .map(
                                id ->
                                        rows.stream()
                                                .filter(
                                                        row ->
                                                                row.evidenceId().equals(id)
                                                                        && row.kind()
                                                                                == MemoryEvidence
                                                                                        .Kind
                                                                                        .USER_MESSAGE)
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new ReviewFailure(
                                                                        "INVALID_REQUEST", false)))
                        .toList();
        if (result.isEmpty()) throw new ReviewFailure("INVALID_REQUEST", false);
        return result;
    }

    private String stablePrefix(
            String jobPrefix, MemoryCommand command, List<MemoryEvidence> evidence) {
        // 稳定身份保留全部业务动作与真实证据版本，但剔除会因自身成功而变化的目标 expectedVersion。
        // 正式 ID 的后缀仍含 expectedVersion 和冻结 revision，旧 CONFLICT 不会毒化新快照重试。
        var action = new TreeMap<String, Object>();
        action.put("action", command.getClass().getSimpleName());
        action.put(
                "evidence",
                evidence.stream().map(e -> List.of(e.evidenceId(), e.messageVersion())).toList());
        action.put("reason", command.reason());
        switch (command) {
            case MemoryCommand.Add add -> {
                action.put("kind", add.kind());
                action.put("content", content.normalize(add.content()));
                action.put("restore", add.restore());
            }
            case MemoryCommand.Replace replace -> {
                action.put("target", replace.memoryId());
                action.put("content", content.normalize(replace.newContent()));
            }
            case MemoryCommand.Remove remove -> action.put("target", remove.memoryId());
            case MemoryCommand.Confirm confirm -> action.put("target", confirm.memoryId());
        }
        return jobPrefix + digest(json.writeValueAsString(action)) + ":";
    }

    private String jobPrefix(MemoryReviewClaim claim) {
        return "mr:" + digest(json.writeValueAsString(List.of(claim.jobId(), claim.epoch()))) + ":";
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
    }

    private static boolean definitive(MemoryStatus status) {
        return switch (status) {
            case UNSAFE_CONTENT, PROTECTED, SUPPRESSED, QUOTA_EXCEEDED -> true;
            default -> false;
        };
    }

    private static final class ReviewFailure extends RuntimeException {
        final String reason;
        final boolean blocked;

        ReviewFailure(String reason, boolean blocked) {
            super(reason);
            this.reason = reason;
            this.blocked = blocked;
        }
    }
}
