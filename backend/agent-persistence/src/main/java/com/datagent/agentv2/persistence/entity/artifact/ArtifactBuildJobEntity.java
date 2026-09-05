package com.datagent.agentv2.persistence.entity.artifact;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 文档构建任务持久化实体；JSON 规格只在持久化编解码边界转换。 */
@Getter
@Setter
@TableName(value = "artifact_build_job", autoResultMap = true)
public final class ArtifactBuildJobEntity extends TenantAuditedEntity {

    private String artifactBuildJobId;
    private String conversationSessionId;
    private String executionId;
    private String conversationMessageId;
    private String artifactType;
    private String operation;
    private String status;

    /** ArtifactSpec 的规范 JSON 对象文本。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String specification;

    private String templateId;
    private String templateVersion;
    private String skillDigest;
    private String scriptDigest;
    private String runtimeVersion;
    private String platform;
    private String failureCode;
    private Instant startedAt;
    private Instant completedAt;

    @Version private Long version;
}
