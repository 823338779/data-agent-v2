package com.datagent.agentv2.artifact.recovery;

import com.datagent.agentv2.artifact.config.ArtifactProperties;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.persistence.service.artifact.ArtifactBuildJobService;
import java.io.IOException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 启动时先收敛数据库中断任务，再清理超过保留期的 staging 目录。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "data-agent.artifact", name = "enabled", havingValue = "true")
public final class ArtifactRecoveryRunner implements ApplicationRunner {

    private static final String PROCESS_RESTARTED = "PROCESS_RESTARTED";

    private final ArtifactBuildJobService buildJobs;
    private final ArtifactStorage storage;
    private final ArtifactProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        boolean changed = buildJobs.failInterruptedBuilds(PROCESS_RESTARTED, now);
        try {
            int deleted =
                    storage.deleteStagingOlderThan(now.minus(properties.getStagingRetention()));
            log.info(
                    "Artifact startup recovery completed: interruptedChanged={}, stagingDeleted={}",
                    changed,
                    deleted);
        } catch (IOException failure) {
            // 数据库状态已经先行收敛；清理失败不能反向删除正式元数据，也不阻止应用恢复服务。
            log.warn("Artifact staging cleanup failed: code=STAGING_CLEANUP_FAILED", failure);
        }
    }
}
