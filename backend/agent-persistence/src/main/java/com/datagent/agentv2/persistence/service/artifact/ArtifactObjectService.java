package com.datagent.agentv2.persistence.service.artifact;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactObjectEntity;
import com.datagent.agentv2.persistence.mapper.artifact.ArtifactObjectMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** 正式 Artifact 元数据的单表持久化服务。 */
@Service
public class ArtifactObjectService extends ServiceImpl<ArtifactObjectMapper, ArtifactObjectEntity> {

    public List<ArtifactObjectEntity> listByArtifactIds(Collection<String> artifactIds) {
        if (artifactIds.isEmpty()) {
            return List.of();
        }
        return lambdaQuery().in(ArtifactObjectEntity::getArtifactId, artifactIds).list();
    }

    public ArtifactObjectEntity findByArtifactId(String artifactId) {
        return lambdaQuery().eq(ArtifactObjectEntity::getArtifactId, artifactId).one();
    }
}
