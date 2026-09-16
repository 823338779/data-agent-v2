package com.datagent.agentv2.memory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 可恢复的会话审查任务；游标范围使用 (reviewedThroughSequence, claimedThroughSequence]。
 *
 * @param jobId 会话任务稳定标识，跨学习代际复用，旧 worker 由 token 隔离
 * @param conversationId 固定身份下唯一会话标识
 * @param learningEpoch 当前学习代际，关闭及重新开启均递增，旧代际不得提交
 * @param reviewedThroughSequence 连续已处理的排除下界；首次为触发用户消息序号减一
 * @param pendingThroughSequence 所有完成事件合并的包含上界，不能小于已处理游标
 * @param claimedThroughSequence 领取冻结的包含上界，运行期间新事件不得扩大
 * @param dirtyGeneration 每次完成标记递增，判断领取期间是否出现新工作
 * @param claimedGeneration 领取时冻结的 generation，不能随新标记变化
 * @param status 任务生命周期；取消与审查完成严格区分
 * @param availableAt 数据库北京时间下的下次可领取时间，不作为租约时钟
 * @param failureCount 当前批次累计失败次数，成功或非运行状态的新事件重置
 * @param failureReason 可安全记录的失败原因码，无失败时为空，不保存模型或用户正文
 * @param owner 当前 worker 标识，未领取或释放后为空
 * @param fencingToken 单调任务级凭证，重新领取或新代际替换递增，释放不清零
 * @param leaseUntil 数据库真实时钟下的租约截止绝对时刻，未持有时为空
 */
public record MemoryReviewJob(
        String jobId,
        String conversationId,
        long learningEpoch,
        long reviewedThroughSequence,
        long pendingThroughSequence,
        long claimedThroughSequence,
        long dirtyGeneration,
        long claimedGeneration,
        Status status,
        LocalDateTime availableAt,
        int failureCount,
        String failureReason,
        String owner,
        long fencingToken,
        Instant leaseUntil) {
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        BLOCKED,
        CANCELLED
    }

    public MemoryReviewJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        if (learningEpoch < 1
                || reviewedThroughSequence < 0
                || pendingThroughSequence < reviewedThroughSequence
                || claimedThroughSequence < reviewedThroughSequence
                || dirtyGeneration < 1
                || claimedGeneration < 0
                || failureCount < 0
                || fencingToken < 0) {
            throw new IllegalArgumentException("审查任务版本或游标不得越界");
        }
    }
}
