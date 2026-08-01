package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_run")
public final class AgentRunEntity extends TenantAuditedEntity {

    /** Run 的业务唯一标识，对应数据库 run_id 列。 */
    private String runId;

    /** Run 所属会话，用于会话内单活约束和事件归档。 */
    private String sessionId;

    /** 触发本次 Run 的用户消息标识；retry 会继续引用原触发消息。 */
    private String triggeringMessageId;

    /** Run 生命周期状态，例如 queued、running、cancelled、done 或 failed。 */
    private String status;

    /** 当前执行阶段，用于流式状态展示和执行边界判断。 */
    private String phase;

    /** 执行尝试序号；queued 时为 0，每次首次领取或 lease 接管后递增。 */
    private Long executionAttempt;

    /** 提交 Run 的用户，用于“单用户最多 3 个 running Run”的容量控制。 */
    private String ownerUserId;

    /** 当前持有执行权的服务实例；仅 running 状态有值，并参与 fencing。 */
    private String ownerInstanceId;

    /** 接入请求的链路追踪标识，不参与业务幂等。 */
    private String requestId;

    /** Run 进入持久化队列的时间，用于稳定的先进先出领取。 */
    private Instant queuedAt;

    /** 当前 owner 的 lease 到期时间；过期后其他实例可以尝试接管。 */
    private Instant leaseExpiresAt;

    /** 当前 owner 最近一次成功续租时间，用于运行状态诊断。 */
    private Instant heartbeatAt;

    /** running Run 收到异步取消请求的时间；owner 轮询到后完成终态化。 */
    private Instant cancellationRequestedAt;

    /** retry 来源 Run 的标识；普通消息提交产生的 Run 为空。 */
    private String retryOfRunId;

    /** 因 lease 失效而发生的接管次数，用于恢复审计和诊断。 */
    private Integer recoveryAttemptCount;

    /** 幂等请求类型，用于区分 message_submission 与 run_retry。 */
    private String requestType;

    /** 客户端提供的幂等键，与会话和 requestType 共同构成唯一请求。 */
    private String requestIdempotencyKey;

    /** 规范化请求负载的 SHA-256，用于识别同一幂等键被不同负载复用。 */
    private String requestPayloadHash;

    /** Run 可消耗的累计活跃执行预算，单位为毫秒。 */
    private Long executionBudgetMs;

    /** 已结算的历史活跃执行时长，waiting/queued 时间不计入，单位为毫秒。 */
    private Long activeExecutionMs;

    /** 当前 running 片段的起点；离开 running 时结算并清空。 */
    private Instant activeSegmentStartedAt;

    /** Run 第一次从 queued 进入 running 的时间，接管时保持不变。 */
    private Instant startedAt;

    /** Run 正常完成或取消终态化的时间。 */
    private Instant completedAt;

    /** Run 进入 failed 终态的时间。 */
    private Instant failedAt;

    /** 稳定的失败代码，例如 RUN_DEADLINE_EXCEEDED。 */
    private String failureCode;

    /** 面向诊断的失败详情，不作为稳定 API 错误码。 */
    private String failureDetail;

    /** 当前活跃片段按剩余预算计算出的硬截止时间；非 running 状态为空。 */
    private Instant hardDeadlineAt;

    /** MyBatis-Plus 乐观锁版本，防止并发状态更新相互覆盖。 */
    @Version private Long version;
}
