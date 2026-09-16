package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话审查任务行，持久化连续游标、冻结范围、代次和任务级 fencing。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_review_job")
public final class MemoryReviewJobEntity extends MemoryRow {
    /** 跨代际复用的任务标识，旧 worker 由 epoch 与 token 隔离。 */
    private String jobId;

    /** 固定身份下唯一的会话标识。 */
    private String conversationId;

    /** 学习开关切换时递增；低代际事件不得覆盖当前任务。 */
    private Long learningEpoch;

    /** 连续处理的排除下界，首次为触发用户消息 sequence 减一。 */
    private Long reviewedThroughSequence;

    /** 完成事件合并后的包含上界，不小于连续游标。 */
    private Long pendingThroughSequence;

    /** 当前领取批次冻结的包含上界，不能被运行期间的标记扩大。 */
    private Long claimedThroughSequence;

    /** 每次标记递增，用于识别领取后的新工作。 */
    private Long dirtyGeneration;

    /** 领取时冻结的 generation，完成仅确认本代范围。 */
    private Long claimedGeneration;

    /** 生命周期枚举名；CANCELLED 不代表已审查完成。 */
    private String status;

    /** 数据库北京时间下的延迟/重试到期时间。 */
    private LocalDateTime availableAt;

    /** 当前批次累计失败次数，成功或非运行状态的新事件重置。 */
    private Integer failureCount;

    /** 安全原因码，无失败为空，禁止存储用户正文。 */
    private String failureReason;

    /** 阻塞时的模型/预算/限制摘要；为空表示旧任务无可比较配置，不能自动激活。 */
    private String blockedConfigurationFingerprint;

    /** 持有任务的 worker 标识，释放后为空。 */
    private String owner;

    /** 任务级单调 fencing token，重新领取/新代际递增，释放不清零。 */
    private Long fencingToken;

    /** 数据库真实时钟对应的绝对到期时刻，未持有时为空。 */
    private Instant leaseUntil;

    public static MemoryReviewJobEntity create() {
        var entity = new MemoryReviewJobEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
