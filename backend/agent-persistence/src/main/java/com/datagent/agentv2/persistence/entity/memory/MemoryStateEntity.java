package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 固定身份的统一记忆版本与持久化开关行，也是上层事务的第一把锁。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_state")
public final class MemoryStateEntity extends MemoryRow {
    private Long revision;
    private Long settingsVersion;
    private Boolean readEnabled;
    private Boolean explicitWriteEnabled;
    private Boolean autoLearningEnabled;
    private Long learningEpoch;
    private LocalDateTime learningEnabledAt;

    public static MemoryStateEntity create() {
        var entity = new MemoryStateEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
