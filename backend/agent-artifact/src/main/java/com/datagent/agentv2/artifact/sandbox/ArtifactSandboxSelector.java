package com.datagent.agentv2.artifact.sandbox;

import java.util.List;
import java.util.Objects;

/** 按启动时检测的平台选择唯一健康 Runner，任何歧义或不健康状态都失败关闭。 */
public final class ArtifactSandboxSelector {

    private final OperatingSystemDetector detector;
    private final List<ArtifactSandboxRunner> runners;

    public ArtifactSandboxSelector(
            OperatingSystemDetector detector, List<ArtifactSandboxRunner> runners) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.runners = List.copyOf(runners);
    }

    public ArtifactSandboxRunner requireHealthyRunner() {
        SandboxPlatform platform = detector.detect();
        List<ArtifactSandboxRunner> matching =
                runners.stream().filter(runner -> runner.platform() == platform).toList();
        if (matching.size() != 1) {
            throw new IllegalStateException(
                    "Artifact sandbox requires exactly one runner for " + platform);
        }
        ArtifactSandboxRunner runner = matching.getFirst();
        SandboxProbe probe = runner.probe();
        if (!probe.healthy()) {
            throw new IllegalStateException(
                    "Artifact sandbox is unhealthy for " + platform + ": " + probe.reasonCode());
        }
        return runner;
    }
}
