package com.datagent.agentv2.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 后台审查领取凭证；租约及 fencing 字段由持久化层在数据库时钟下验证。
 *
 * @param jobId 被领取的审查任务 ID
 * @param conversationId 任务所属会话 ID
 * @param epoch 领取时冻结的学习代次，学习开关切换后旧代次不可提交
 * @param jobToken 任务级单调 fencing token，阻止同一任务的旧 worker 提交
 * @param userToken 用户级单调 fencing token，阻止同一固定身份的旧租约持有者提交
 * @param owner 当前 worker 的稳定所有者标识
 * @param claimedGeneration 领取时冻结的任务 generation，仅完成该代及此前已领取范围
 * @param fromSequence 本批连续消息范围的起始 sequence，包含该值
 * @param throughSequence 本批连续消息范围的结束 sequence，包含该值
 * @param leaseUntil 以数据库时钟判定的租约到期绝对时刻；使用 {@link Instant} 对应 timestamptz
 */
public record MemoryReviewClaim(
        String jobId,
        String conversationId,
        long epoch,
        long jobToken,
        long userToken,
        String owner,
        long claimedGeneration,
        long fromSequence,
        long throughSequence,
        Instant leaseUntil) {
    public MemoryReviewClaim {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (epoch < 1
                || jobToken < 1
                || userToken < 1
                || claimedGeneration < 1
                || fromSequence < 0
                || throughSequence < fromSequence) {
            throw new IllegalArgumentException("后台审查领取参数不得越界");
        }
    }
}
