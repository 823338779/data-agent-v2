package com.datagent.agentv2.memory;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 个人记忆管理接口的应用编排层。
 *
 * <p>这里只构造可信 UI 调用上下文和领域命令；身份、证据时间、幂等与事务仍由统一 {@link MemoryService} 边界负责。
 */
@Service
@RequiredArgsConstructor
public class PersonalMemoryApplicationService {

    private final MemoryService memory;

    public PersonalMemoryListView list(MemoryKind kind, String cursor, int limit) {
        return PersonalMemoryListView.composeFromSnapshot(memory.snapshot(), kind, cursor, limit);
    }

    public MemoryOutcome create(
            String requestId, MemoryKind kind, String content, boolean restore) {
        return requireSuccess(
                memory.execute(
                        ui(requestId),
                        new MemoryCommand.Add(
                                kind,
                                content,
                                MemoryWriteReason.USER_REQUESTED,
                                List.of(),
                                restore)));
    }

    public MemoryOutcome replace(
            String requestId, String id, long expectedVersion, String content) {
        return requireSuccess(
                memory.execute(
                        ui(requestId),
                        new MemoryCommand.Replace(
                                id,
                                expectedVersion,
                                content,
                                MemoryWriteReason.USER_REQUESTED,
                                List.of())));
    }

    /** 删除成功不需要再查询条目；REMOVED outcome 的 targetExists 按领域约定为 false。 */
    public void remove(String requestId, String id, long expectedVersion) {
        requireSuccess(
                memory.execute(
                        ui(requestId),
                        new MemoryCommand.Remove(
                                id,
                                expectedVersion,
                                MemoryWriteReason.USER_REQUESTED,
                                List.of())));
    }

    /** 读取管理设置不受 readEnabled 影响，避免关闭读取后无法查看或恢复设置。 */
    public MemorySettingsView settings() {
        return MemorySettingsView.composeFromState(memory.snapshot().state());
    }

    /** 设置入口独立于 explicitWriteEnabled，允许用户重新开启先前关闭的显式写入。 */
    public MemorySettingsView updateSettings(
            String requestId,
            long expectedVersion,
            Boolean readEnabled,
            Boolean explicitWriteEnabled,
            Boolean autoLearningEnabled) {
        var changed =
                new MemorySettingsChange(
                        expectedVersion,
                        readEnabled,
                        explicitWriteEnabled,
                        autoLearningEnabled);
        return MemorySettingsView.composeFromState(memory.updateSettings(requestId, changed));
    }

    private static MemoryWriteContext ui(String requestId) {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.UI, requestId, null, null, null, null);
    }

    private static MemoryOutcome requireSuccess(MemoryOutcome outcome) {
        if (outcome.successful()) {
            return outcome;
        }
        throw new MemoryException(outcome.status(), safeMessage(outcome.status()));
    }

    /** outcome 不携带用户正文；这里只补充可稳定公开的拒绝说明。 */
    private static String safeMessage(MemoryStatus status) {
        return switch (status) {
            case INVALID_REQUEST -> "记忆请求无效";
            case UNSAFE_CONTENT -> "记忆内容不符合安全要求";
            case CONFLICT -> "记忆版本或操作键已发生冲突";
            case DUPLICATE_CONFLICT -> "记忆正文与其他条目重复";
            case NOT_FOUND -> "记忆条目不存在";
            case PROTECTED -> "记忆条目受人工保护";
            case SUPPRESSED -> "记忆已被删除抑制";
            case QUOTA_EXCEEDED -> "记忆超过分类容量限制";
            case LEARNING_DISABLED -> "当前记忆写入能力已关闭";
            case LEASE_LOST -> "记忆写入资格已失效";
            case UNAVAILABLE -> "记忆服务暂不可用";
            case CREATED, ALREADY_EXISTS, UPDATED, REMOVED, CONFIRMED ->
                    throw new IllegalArgumentException("成功状态不得转换为异常");
        };
    }
}
