package com.datagent.agentv2.artifact.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Artifact 能力的本机存储、资源上限和失败关闭配置。 */
@Getter
@Setter
@Validated
@ConfigurationProperties("data-agent.artifact")
public final class ArtifactProperties {

    private boolean enabled;

    @NotNull private Path rootDirectory = Path.of("./data/artifacts");

    @Min(1)
    private int maxConcurrentBuilds = 1;

    @NotNull private Duration maxBuildDuration = Duration.ofSeconds(180);

    @Min(1)
    @Max(3)
    private int maxBuildAttemptsPerExecution = 3;

    @Min(1)
    private long maxInputBytes = 20L * 1024L * 1024L;

    @Min(1)
    private long maxOutputBytes = 50L * 1024L * 1024L;

    @Min(1)
    private long minFreeDiskBytes = 1024L * 1024L * 1024L;

    @NotNull private Duration stagingRetention = Duration.ofHours(24);
}
