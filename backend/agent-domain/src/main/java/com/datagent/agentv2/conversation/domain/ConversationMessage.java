package com.datagent.agentv2.conversation.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 会话消息聚合；身份固定，正文和过程仅通过受约束的业务操作变更。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConversationMessage {

    private static final String ERROR_DUPLICATE_PROCESS_ITEM_ID =
            "duplicate processItemId in thinkingProcess";
    private static final String ERROR_DUPLICATE_PROCESS_SEQUENCE =
            "duplicate sequence in thinkingProcess";
    private static final String ERROR_PROCESS_SEQUENCE_GAP =
            "new process item sequence must follow the current maximum";
    private static final String ERROR_PROCESS_ITEM_NOT_FOUND = "process item was not found";
    private static final String ERROR_PROCESS_ITEM_ID_CHANGED =
            "replacement process item identity changed";
    private static final String ERROR_PROCESS_ITEM_SEQUENCE_CHANGED =
            "replacement process item sequence changed";
    private static final String ERROR_PROCESS_ITEM_TYPE_CHANGED =
            "replacement process item type changed";
    private static final String ERROR_TERMINAL_PROCESS_ITEM =
            "terminal process item cannot transition";
    private static final String ERROR_ILLEGAL_PROCESS_TRANSITION =
            "illegal process item state transition";
    private static final String ERROR_THINKING_INITIAL_STATE =
            "thinking process item must start RUNNING";
    private static final String ERROR_TOOL_INITIAL_STATE = "tool process item must start PREPARED";

    /** 消息业务唯一标识。 */
    private String conversationMessageId;

    /** 消息所属会话标识。 */
    private String conversationSessionId;

    /** 产生或消费消息的 Agent 执行标识。 */
    private String executionId;

    /** 消息角色。 */
    private MessageRole role;

    /** 消息正文。 */
    private String content;

    /** 与真实消息正文绑定的有序生成过程；用户消息当前固定为空列表。 */
    private List<ThinkingProcessItem> thinkingProcess = List.of();

    /** 消息在会话内的单调递增序号。 */
    private Long conversationMessageSequence;

    /** 用户消息作者标识。 */
    private String authorUserId;

    /** 消息乐观锁版本。 */
    private Long version;

    /** 数据库生成的记忆证据创建时间（北京时间），独立于应用审计时钟；迁移前消息或未重新读取的新增对象为空，不可用于记忆授权。 */
    private LocalDateTime memoryEvidenceCreatedAt;

    /** 用户可见的消息创建时间。 */
    private LocalDateTime createdAt;

    /** 从完整存储快照恢复领域状态；Builder 只负责组装，构造时统一校验不变量。 */
    @Builder
    private ConversationMessage(
            String conversationMessageId,
            String conversationSessionId,
            String executionId,
            MessageRole role,
            String content,
            List<ThinkingProcessItem> thinkingProcess,
            Long conversationMessageSequence,
            String authorUserId,
            Long version,
            LocalDateTime memoryEvidenceCreatedAt,
            LocalDateTime createdAt) {
        this.conversationMessageId =
                DomainInvariants.text(conversationMessageId, "conversationMessageId");
        this.conversationSessionId =
                DomainInvariants.text(conversationSessionId, "conversationSessionId");
        this.executionId = executionId;
        this.role = java.util.Objects.requireNonNull(role, "role");
        this.content = java.util.Objects.requireNonNull(content, "content");
        restoreThinkingProcess(thinkingProcess);
        this.conversationMessageSequence =
                DomainInvariants.nonnegative(
                        conversationMessageSequence, "conversationMessageSequence");
        this.authorUserId = authorUserId;
        this.version = DomainInvariants.nonnegative(version, "version");
        this.memoryEvidenceCreatedAt = memoryEvidenceCreatedAt;
        this.createdAt = createdAt;
    }

    /** 组装用户提交消息。 */
    public static ConversationMessage user(
            String messageId,
            String sessionId,
            String content,
            long sequence,
            String authorUserId) {
        ConversationMessage message =
                base(messageId, sessionId, MessageRole.USER, content, sequence);
        message.authorUserId = authorUserId;
        return message;
    }

    /** 组装与执行绑定的唯一 assistant 中心占位消息。 */
    public static ConversationMessage assistantPlaceholder(
            String messageId, String sessionId, String executionId, long sequence) {
        ConversationMessage message =
                base(messageId, sessionId, MessageRole.ASSISTANT, "", sequence);
        message.executionId = DomainInvariants.text(executionId, "executionId");
        message.thinkingProcess = List.of();
        return message;
    }

    /** 组装与执行关联的 assistant 消息。 */
    public static ConversationMessage assistant(
            String messageId, String sessionId, String executionId, String content, long sequence) {
        ConversationMessage message =
                base(messageId, sessionId, MessageRole.ASSISTANT, content, sequence);
        message.executionId = DomainInvariants.text(executionId, "executionId");
        return message;
    }

    /** 将已持久化的用户消息关联到其触发的执行。 */
    public void associateExecution(String executionId) {
        DomainInvariants.text(executionId, "executionId");
        if (this.executionId != null && !this.executionId.equals(executionId)) {
            throw new IllegalStateException("Message already belongs to another execution");
        }
        this.executionId = executionId;
    }

    /** 在执行终态把预建 assistant 占位正文替换为真实模型回答。 */
    public void completeAssistantContent(String content) {
        if (role != MessageRole.ASSISTANT) {
            throw new IllegalStateException("Only assistant content can be completed");
        }
        this.content = Objects.requireNonNull(content, "content");
    }

    /** 使用不可变副本替换过程列表，避免持久化后被调用方继续修改。 */
    private void restoreThinkingProcess(List<ThinkingProcessItem> thinkingProcess) {
        List<ThinkingProcessItem> candidate =
                thinkingProcess == null ? List.of() : List.copyOf(thinkingProcess);
        validateUniqueProcessItems(candidate);
        this.thinkingProcess = candidate;
    }

    /** 返回下一节点序号，并同时拒绝历史文档中的重复 UUID 或序号。 */
    public long nextProcessSequence() {
        validateUniqueProcessItems(thinkingProcess);
        return thinkingProcess.stream().mapToLong(ThinkingProcessItem::sequence).max().orElse(0L)
                + 1L;
    }

    /** 追加一个符合类型初始状态且序号连续的新节点。 */
    public void appendProcessItem(ThinkingProcessItem item) {
        Objects.requireNonNull(item, "item");
        long expectedSequence = nextProcessSequence();
        if (item.sequence() != expectedSequence) {
            throw new IllegalStateException(ERROR_PROCESS_SEQUENCE_GAP);
        }
        validateInitialState(item);

        // 仅在全部校验完成后替换不可变列表，异常不会留下半成品。
        List<ThinkingProcessItem> changed = new ArrayList<>(thinkingProcess);
        changed.add(item);
        validateUniqueProcessItems(changed);
        thinkingProcess = List.copyOf(changed);
    }

    /** 按 UUID 替换已有节点，并执行不可逆生命周期转换校验。 */
    public void replaceProcessItem(UUID processItemId, ThinkingProcessItem replacement) {
        Objects.requireNonNull(processItemId, "processItemId");
        Objects.requireNonNull(replacement, "replacement");
        validateUniqueProcessItems(thinkingProcess);
        int index = findProcessItemIndex(processItemId);
        ThinkingProcessItem current = thinkingProcess.get(index);
        validateReplacement(current, replacement);

        // 校验成功后一次性生成新列表，确保失败转换不会修改原 JSON 文档。
        List<ThinkingProcessItem> changed = new ArrayList<>(thinkingProcess);
        changed.set(index, replacement);
        validateUniqueProcessItems(changed);
        thinkingProcess = List.copyOf(changed);
    }

    /** 按 UUID 获取节点；找不到时使用统一状态异常。 */
    public ThinkingProcessItem requireProcessItem(UUID processItemId) {
        return thinkingProcess.get(findProcessItemIndex(processItemId));
    }

    /** 校验外层列表的 UUID 和序号均唯一；不设置列表总大小上限。 */
    private static void validateUniqueProcessItems(List<ThinkingProcessItem> items) {
        Set<UUID> ids = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (ThinkingProcessItem item : items) {
            Objects.requireNonNull(item, "thinkingProcess item");
            if (!ids.add(item.processItemId())) {
                throw new IllegalStateException(ERROR_DUPLICATE_PROCESS_ITEM_ID);
            }
            if (!sequences.add(item.sequence())) {
                throw new IllegalStateException(ERROR_DUPLICATE_PROCESS_SEQUENCE);
            }
        }
    }

    /** 不同节点类型具有不同的合法初始状态。 */
    private static void validateInitialState(ThinkingProcessItem item) {
        if (item instanceof ModelThinkingProcessItem
                && item.status() != ThinkingProcessStatus.RUNNING) {
            throw new IllegalStateException(ERROR_THINKING_INITIAL_STATE);
        }
        if (item instanceof ToolThinkingProcessItem
                && item.status() != ThinkingProcessStatus.PREPARED) {
            throw new IllegalStateException(ERROR_TOOL_INITIAL_STATE);
        }
    }

    /** 节点身份字段保持稳定，状态只能沿既定生命周期前进。 */
    private static void validateReplacement(
            ThinkingProcessItem current, ThinkingProcessItem replacement) {
        if (!current.processItemId().equals(replacement.processItemId())) {
            throw new IllegalStateException(ERROR_PROCESS_ITEM_ID_CHANGED);
        }
        if (current.sequence() != replacement.sequence()) {
            throw new IllegalStateException(ERROR_PROCESS_ITEM_SEQUENCE_CHANGED);
        }
        if (current.type() != replacement.type()) {
            throw new IllegalStateException(ERROR_PROCESS_ITEM_TYPE_CHANGED);
        }
        if (isTerminal(current.status())) {
            throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM);
        }

        // thinking 允许 RUNNING 内容检查点；工具必须先 PREPARED 再 RUNNING，二者均可失败收敛。
        boolean legal =
                current instanceof ModelThinkingProcessItem
                        ? current.status() == ThinkingProcessStatus.RUNNING
                                && replacement.status() != ThinkingProcessStatus.PREPARED
                        : (current.status() == ThinkingProcessStatus.PREPARED
                                        && (replacement.status() == ThinkingProcessStatus.RUNNING
                                                || replacement.status()
                                                        == ThinkingProcessStatus.FAILED))
                                || (current.status() == ThinkingProcessStatus.RUNNING
                                        && (replacement.status() == ThinkingProcessStatus.SUCCEEDED
                                                || replacement.status()
                                                        == ThinkingProcessStatus.FAILED));
        if (!legal) {
            throw new IllegalStateException(ERROR_ILLEGAL_PROCESS_TRANSITION);
        }
    }

    private int findProcessItemIndex(UUID processItemId) {
        for (int index = 0; index < thinkingProcess.size(); index++) {
            if (thinkingProcess.get(index).processItemId().equals(processItemId)) {
                return index;
            }
        }
        throw new IllegalStateException(ERROR_PROCESS_ITEM_NOT_FOUND);
    }

    private static boolean isTerminal(ThinkingProcessStatus status) {
        return status == ThinkingProcessStatus.SUCCEEDED || status == ThinkingProcessStatus.FAILED;
    }

    /** 成功提交后同步版本和数据库生成的证据时间，不改变正文及过程状态。 */
    public void acknowledgePersistence(
            long persistedVersion, LocalDateTime createdAt, LocalDateTime memoryEvidenceCreatedAt) {
        version = DomainInvariants.advancedVersion(version, persistedVersion);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.memoryEvidenceCreatedAt = memoryEvidenceCreatedAt;
    }

    private static ConversationMessage base(
            String messageId, String sessionId, MessageRole role, String content, long sequence) {
        ConversationMessage message = new ConversationMessage();
        message.conversationMessageId = DomainInvariants.text(messageId, "messageId");
        message.conversationSessionId = DomainInvariants.text(sessionId, "sessionId");
        message.role = Objects.requireNonNull(role, "role");
        message.content = Objects.requireNonNull(content, "content");
        message.conversationMessageSequence = DomainInvariants.nonnegative(sequence, "sequence");
        message.version = 0L;
        return message;
    }
}
