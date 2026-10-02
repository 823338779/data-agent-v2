package com.datagent.agentv2.memory.tool;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemoryWriteContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 按 execution 与模型调用序号保存可信记忆调用快照。
 *
 * <p>工具在虚拟线程执行，不能依赖请求线程 ThreadLocal。显式键也使每轮关闭和 execution 级兜底清理可验证。
 */
@Component
public final class MemoryInvocationRegistry {

    private static final String ERROR_CONTEXT_NOT_FOUND = "Memory invocation context is not open";
    private static final String ERROR_CONTEXT_EXISTS = "Memory invocation context is already open";
    private static final String ERROR_REVISION_CHANGED =
            "Memory invocation revision changed unexpectedly";

    private final ConcurrentMap<InvocationKey, FrozenInvocation> invocations =
            new ConcurrentHashMap<>();

    public void open(
            String executionId,
            long invocationSequence,
            MemorySnapshot snapshot,
            List<MemoryEvidence> evidence) {
        InvocationKey key = key(executionId, invocationSequence);
        FrozenInvocation frozen = new FrozenInvocation(executionId, snapshot, evidence);
        if (invocations.putIfAbsent(key, frozen) != null) {
            throw new IllegalStateException(ERROR_CONTEXT_EXISTS);
        }
    }

    /** 未成功冻结快照的轮次不可执行记忆工具，避免读取失败后猜测写入 revision。 */
    public boolean isOpen(AgentTool.InvocationContext context) {
        return invocations.containsKey(
                key(context.executionId(), context.modelInvocationSequence()));
    }

    public MemoryWriteContext contextFor(AgentTool.InvocationContext context) {
        FrozenInvocation frozen = require(context.executionId(), context.modelInvocationSequence());
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.MAIN,
                context.idempotencyKey(),
                context.executionId(),
                frozen.conversationId(),
                frozen.revision().get(),
                null,
                MemoryToolPrompts.MAIN_VERSION);
    }

    /** 返回本轮冻结快照；list 另读当前一致快照，但不能把外部 revision 偷换为新的写入资格。 */
    public MemorySnapshot snapshotFor(AgentTool.InvocationContext context) {
        return require(context.executionId(), context.modelInvocationSequence()).snapshot();
    }

    /** 测试及后续运行时元数据只读访问冻结证据，返回不可变副本。 */
    public List<MemoryEvidence> evidenceFor(AgentTool.InvocationContext context) {
        return require(context.executionId(), context.modelInvocationSequence()).evidence();
    }

    /** 模型只能引用模型调用开始前已冻结的真实用户消息 ID。 */
    public void requireAllowedEvidence(
            AgentTool.InvocationContext context, List<String> evidenceIds) {
        FrozenInvocation frozen = require(context.executionId(), context.modelInvocationSequence());
        if (evidenceIds.isEmpty() || !frozen.evidenceIds().containsAll(evidenceIds)) {
            throw new IllegalArgumentException("evidence_message_ids are outside the frozen scope");
        }
    }

    public void advance(
            String executionId,
            long invocationSequence,
            long expectedRevision,
            long committedRevision) {
        FrozenInvocation frozen = require(executionId, invocationSequence);
        // 幂等重放返回历史 revision。历史值不得让同轮已经推进的冻结版本倒退。
        if (committedRevision <= expectedRevision) {
            return;
        }
        if (!frozen.revision().compareAndSet(expectedRevision, committedRevision)) {
            throw new IllegalStateException(ERROR_REVISION_CHANGED);
        }
    }

    public void close(String executionId, long invocationSequence) {
        invocations.remove(key(executionId, invocationSequence));
    }

    public void closeExecution(String executionId) {
        if (executionId == null) {
            return;
        }
        invocations.keySet().removeIf(key -> executionId.equals(key.executionId()));
    }

    private FrozenInvocation require(String executionId, long invocationSequence) {
        FrozenInvocation frozen = invocations.get(key(executionId, invocationSequence));
        if (frozen == null) {
            throw new IllegalStateException(ERROR_CONTEXT_NOT_FOUND);
        }
        return frozen;
    }

    private static InvocationKey key(String executionId, long invocationSequence) {
        Objects.requireNonNull(executionId, "executionId");
        if (executionId.isBlank() || invocationSequence < 1) {
            throw new IllegalArgumentException("Memory invocation key is invalid");
        }
        return new InvocationKey(executionId, invocationSequence);
    }

    private record InvocationKey(String executionId, long invocationSequence) {}

    private record FrozenInvocation(
            MemorySnapshot snapshot,
            List<MemoryEvidence> evidence,
            Set<String> evidenceIds,
            String conversationId,
            AtomicLong revision) {
        private FrozenInvocation(
                String executionId, MemorySnapshot snapshot, List<MemoryEvidence> evidence) {
            this(snapshot, validatedEvidence(executionId, evidence));
        }

        private FrozenInvocation(MemorySnapshot snapshot, List<MemoryEvidence> evidence) {
            this(
                    Objects.requireNonNull(snapshot, "snapshot"),
                    evidence,
                    ids(evidence),
                    conversation(evidence),
                    new AtomicLong(snapshot.state().revision()));
        }

        private static List<MemoryEvidence> validatedEvidence(
                String executionId, List<MemoryEvidence> evidence) {
            List<MemoryEvidence> frozen = List.copyOf(evidence);
            for (MemoryEvidence item : frozen) {
                if (item.kind() != MemoryEvidence.Kind.USER_MESSAGE
                        || !executionId.equals(item.executionId())) {
                    throw new IllegalArgumentException(
                            "Memory evidence must be a user message from this execution");
                }
            }
            return frozen;
        }

        private static Set<String> ids(List<MemoryEvidence> evidence) {
            var ids = new HashSet<String>();
            for (MemoryEvidence item : evidence) {
                if (!ids.add(item.evidenceId())) {
                    throw new IllegalArgumentException("Memory evidence IDs must be unique");
                }
            }
            return Set.copyOf(ids);
        }

        private static String conversation(List<MemoryEvidence> evidence) {
            String conversationId = null;
            for (MemoryEvidence item : evidence) {
                if (conversationId == null) {
                    conversationId = item.conversationId();
                }
                if (!Objects.equals(conversationId, item.conversationId())) {
                    throw new IllegalArgumentException("Memory evidence must share a conversation");
                }
            }
            return conversationId;
        }
    }
}
