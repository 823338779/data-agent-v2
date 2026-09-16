package com.datagent.agentv2.memory;

import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 写入授权规则，校验可信调用模式、能力开关及并发前置条件。 */
@Component
public class MemoryWritePolicy {
    public void authorize(
            MemoryWriteContext context,
            MemoryCommand command,
            MemoryEntry target,
            MemoryState state) {
        if (command instanceof MemoryCommand.Confirm
                && context.caller() != MemoryWriteContext.Caller.BACKGROUND)
            reject(MemoryStatus.INVALID_REQUEST);
        boolean explicit = explicit(context, command);
        if (context.requestId().isBlank()
                || (context.caller() == MemoryWriteContext.Caller.UI
                        && (command.reason() != MemoryWriteReason.USER_REQUESTED
                                || !command.evidenceMessageIds().isEmpty()))
                || (context.caller() == MemoryWriteContext.Caller.BACKGROUND
                        && command.reason() != MemoryWriteReason.AGENT_INFERRED)) {
            reject(MemoryStatus.INVALID_REQUEST);
        }
        if (explicit ? !state.explicitWriteEnabled() : !state.autoLearningEnabled()) {
            reject(MemoryStatus.LEARNING_DISABLED);
        }
        if (context.caller() != MemoryWriteContext.Caller.UI) {
            if (context.expectedRevision() == null
                    || blank(context.conversationId())
                    || (context.caller() == MemoryWriteContext.Caller.MAIN
                            && blank(context.executionId()))) {
                reject(MemoryStatus.INVALID_REQUEST);
            }
            if (context.expectedRevision() != state.revision()) {
                reject(MemoryStatus.CONFLICT);
            }
        }
        if (!(command instanceof MemoryCommand.Add)) {
            if (target == null) reject(MemoryStatus.NOT_FOUND);
            long expectedVersion =
                    switch (command) {
                        case MemoryCommand.Replace replace -> replace.expectedVersion();
                        case MemoryCommand.Remove remove -> remove.expectedVersion();
                        case MemoryCommand.Confirm confirm -> confirm.expectedVersion();
                        case MemoryCommand.Add ignored ->
                                throw new IllegalStateException("新增没有目标版本");
                    };
            if (target.version() != expectedVersion) reject(MemoryStatus.CONFLICT);
            if (target.userProtected() && !explicit) reject(MemoryStatus.PROTECTED);
        }
    }

    /** 验证端口返回的持久化来源与完整请求集合，不能把角色/作者正确但不属于本轮的消息当授权。 */
    void validateEvidence(
            MemoryWriteContext context, MemoryCommand command, List<MemoryEvidence> evidence) {
        if (context.caller() == MemoryWriteContext.Caller.UI) return;
        var ids = new HashSet<>(command.evidenceMessageIds());
        if (ids.isEmpty()
                || ids.stream().anyMatch(MemoryWritePolicy::blank)
                || evidence == null
                || evidence.size() != ids.size()) reject(MemoryStatus.INVALID_REQUEST);
        for (var item : evidence) {
            if (!ids.remove(item.evidenceId())
                    || item.kind() != MemoryEvidence.Kind.USER_MESSAGE
                    || !OperatorContext.requiredUserId().equals(item.authorUserId())
                    || !Objects.equals(context.conversationId(), item.conversationId())
                    || item.messageVersion() < 1
                    || item.content().isBlank()) reject(MemoryStatus.INVALID_REQUEST);
            if (context.caller() == MemoryWriteContext.Caller.MAIN
                    && !Objects.equals(context.executionId(), item.executionId()))
                reject(MemoryStatus.INVALID_REQUEST);
            if (context.caller() == MemoryWriteContext.Caller.BACKGROUND) {
                var claim = context.reviewClaim();
                if (blank(item.executionId())
                        || item.sequence() <= claim.fromSequence()
                        || item.sequence() > claim.throughSequence())
                    reject(MemoryStatus.INVALID_REQUEST);
            }
        }
    }

    /** 对话恢复必须由删除之后的新用户消息明确授权；UI 本次操作本身是新授权。 */
    void validateRestore(
            MemoryWriteContext context,
            MemoryCommand command,
            MemorySuppression suppression,
            List<MemoryEvidence> evidence) {
        if (!(command instanceof MemoryCommand.Add add)
                || !add.restore()
                || !explicit(context, command)) reject(MemoryStatus.SUPPRESSED);
        if (context.caller() == MemoryWriteContext.Caller.MAIN
                && (context.expectedRevision() < suppression.deletedRevision()
                        || evidence.stream()
                                .noneMatch(
                                        item -> item.createdAt().isAfter(suppression.deletedAt()))))
            reject(MemoryStatus.SUPPRESSED);
    }

    static boolean explicit(MemoryWriteContext context, MemoryCommand command) {
        return context.caller() != MemoryWriteContext.Caller.BACKGROUND
                && command.reason() == MemoryWriteReason.USER_REQUESTED;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void reject(MemoryStatus status) {
        throw new MemoryException(status, "记忆写入条件不满足");
    }
}
