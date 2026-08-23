package com.datagent.agentv2.context.lsm.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化一个租户会话当前发布的 LSM 上下文视图。 */
@Getter
@Setter
@TableName("conversation_context_lsm_manifest")
public final class LsmContextManifestEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** manifest 的业务唯一标识。 */
    private String contextManifestId;

    /** manifest 所属租户。 */
    private String tenantId;

    /** manifest 所属会话。 */
    private String conversationSessionId;

    /** 当前发布 ROOT 段的业务标识。 */
    private String activeRootSegmentId;

    /** 按覆盖区间排序的活动摘要段业务标识 JSON 数组。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String activeSegmentIds;

    /** 等待压缩的原始历史首轮序号。 */
    private Long pendingRawStartTurnSequence;

    /** 等待压缩的原始历史末轮序号。 */
    private Long pendingRawEndTurnSequence;

    /** Hot 区间起始轮次序号。 */
    private Long hotFromTurnSequence;

    /** 已评估输入的最高消息序号。 */
    private Long evaluatedUpToMessageSequence;

    /** 当前视图适用的策略版本。 */
    private Integer effectivePolicyVersion;

    /** 当前视图适用的合并扇出。 */
    private Integer effectiveFanout;

    /** 当前视图适用的最大 LSM 层级。 */
    private Integer effectiveMaxLevel;

    /** 每次发布单调递增的 manifest 代次。 */
    private Long generation;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 创建记录的插件操作者标识。 */
    private String createdBy;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 最后更新记录的插件操作者标识。 */
    private String updatedBy;

    /** 逻辑删除标记。 */
    private boolean deleted;

    /** 乐观锁版本，作为 manifest CAS 条件之一。 */
    @Version private Long version;
}
