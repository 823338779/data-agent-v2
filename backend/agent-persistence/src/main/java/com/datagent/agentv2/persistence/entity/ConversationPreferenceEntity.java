package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 保存用户级会话偏好及跨设备完整重排使用的 ordering version。 */
@Getter
@Setter
@TableName("conversation_preference")
public final class ConversationPreferenceEntity {

    /** 偏好所属租户，和 userId 共同构成主键。 */
    private String tenantId;

    /** 偏好所属用户业务标识。 */
    private String userId;

    /** 每次成功完整重排后单调递增的业务版本。 */
    private Long orderingVersion;

    /** 用户偏好首次建立的时间。 */
    private Instant createdAt;

    /** 排序版本最后变化的时间。 */
    private Instant updatedAt;

    /** 持久化行乐观锁版本。 */
    @Version private Long version;
}
