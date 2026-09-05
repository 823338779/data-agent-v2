package com.datagent.agentv2.persistence.service.artifact;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.artifact.domain.ArtifactBuildStatus;
import com.datagent.agentv2.persistence.entity.artifact.ArtifactBuildJobEntity;
import com.datagent.agentv2.persistence.mapper.artifact.ArtifactBuildJobMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 构建任务单表持久化服务。 */
@Service
public class ArtifactBuildJobService
        extends ServiceImpl<ArtifactBuildJobMapper, ArtifactBuildJobEntity> {

    /** 将进程重启前遗留的非终态任务一次性收敛为失败。 */
    public boolean failInterruptedBuilds(String failureCode, Instant completedAt) {
        List<String> active =
                List.of(
                        ArtifactBuildStatus.PREPARED.name(),
                        ArtifactBuildStatus.RUNNING.name(),
                        ArtifactBuildStatus.RENDERING.name(),
                        ArtifactBuildStatus.VERIFYING.name());
        return lambdaUpdate()
                .in(ArtifactBuildJobEntity::getStatus, active)
                .set(ArtifactBuildJobEntity::getStatus, ArtifactBuildStatus.FAILED.name())
                .set(ArtifactBuildJobEntity::getFailureCode, failureCode)
                .set(ArtifactBuildJobEntity::getCompletedAt, completedAt)
                .update();
    }
}
