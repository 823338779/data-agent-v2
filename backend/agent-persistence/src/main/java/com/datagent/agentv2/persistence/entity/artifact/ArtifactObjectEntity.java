package com.datagent.agentv2.persistence.entity.artifact;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 已原子发布且通过校验的不可变 Artifact 元数据。 */
@Getter
@Setter
@TableName("artifact_object")
public final class ArtifactObjectEntity extends TenantAuditedEntity {

    private String artifactId;
    private String conversationSessionId;
    private String artifactBuildJobId;
    private String sourceArtifactId;
    private Integer specificationVersion;
    private String artifactType;
    private String status;
    private String displayName;
    private String mediaType;
    private String storageKey;
    private Long sizeBytes;
    private String sha256;
    private Integer pageOrSheetCount;

    @Version private Long version;
}
