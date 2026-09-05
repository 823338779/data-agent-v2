package com.datagent.agentv2.artifact.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArtifactSandboxSelectorTest {

    @Test
    void selectsTheOnlyHealthyRunnerForCurrentPlatform() {
        ArtifactSandboxRunner windows = runner(SandboxPlatform.WINDOWS, SandboxProbe.ready());
        ArtifactSandboxRunner linux = runner(SandboxPlatform.LINUX, SandboxProbe.ready());

        assertThat(selector(windows, linux).requireHealthyRunner()).isSameAs(windows);
    }

    @Test
    void failsClosedForMissingDuplicateOrUnhealthyRunner() {
        assertThatThrownBy(() -> selector().requireHealthyRunner())
                .hasMessageContaining("exactly one runner");
        assertThatThrownBy(
                        () ->
                                selector(
                                                runner(
                                                        SandboxPlatform.WINDOWS,
                                                        SandboxProbe.ready()),
                                                runner(
                                                        SandboxPlatform.WINDOWS,
                                                        SandboxProbe.ready()))
                                        .requireHealthyRunner())
                .hasMessageContaining("exactly one runner");
        assertThatThrownBy(
                        () ->
                                selector(
                                                runner(
                                                        SandboxPlatform.WINDOWS,
                                                        new SandboxProbe(false, "RUNTIME_MISSING")))
                                        .requireHealthyRunner())
                .hasMessageContaining("RUNTIME_MISSING");
    }

    private static ArtifactSandboxSelector selector(ArtifactSandboxRunner... runners) {
        return new ArtifactSandboxSelector(
                new OperatingSystemDetector(() -> "Windows 11"), java.util.List.of(runners));
    }

    private static ArtifactSandboxRunner runner(SandboxPlatform platform, SandboxProbe probe) {
        return new ArtifactSandboxRunner() {
            @Override
            public SandboxPlatform platform() {
                return platform;
            }

            @Override
            public SandboxProbe probe() {
                return probe;
            }

            @Override
            public SandboxRunResult run(SandboxRunRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void cancel(String buildJobId) {}
        };
    }
}
