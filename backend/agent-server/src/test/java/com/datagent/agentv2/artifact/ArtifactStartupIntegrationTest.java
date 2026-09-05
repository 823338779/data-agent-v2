package com.datagent.agentv2.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.artifact.config.ArtifactConfiguration;
import com.datagent.agentv2.artifact.sandbox.ArtifactSandboxRunner;
import com.datagent.agentv2.artifact.sandbox.ArtifactStartupValidator;
import com.datagent.agentv2.artifact.sandbox.OperatingSystemDetector;
import com.datagent.agentv2.artifact.sandbox.SandboxProbe;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArtifactStartupIntegrationTest {

    @TempDir Path root;

    @Test
    void disabledCapabilityStartsWithoutRunner() {
        context(false).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledCapabilityFailsWithoutRunner() {
        context(true)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("requires exactly one runner");
                        });
    }

    @Test
    void enabledCapabilityRequiresAHealthyCurrentPlatformRunner() {
        var platform = new OperatingSystemDetector().detect();
        context(true)
                .withBean(
                        ArtifactSandboxRunner.class,
                        () ->
                                new FakeArtifactSandboxRunner(
                                        platform, new SandboxProbe(false, "UNAVAILABLE")))
                .run(context -> assertThat(context).hasFailed());
        context(true)
                .withBean(
                        ArtifactSandboxRunner.class,
                        () -> new FakeArtifactSandboxRunner(platform, SandboxProbe.ready()))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ArtifactStartupValidator.class);
                        });
    }

    private ApplicationContextRunner context(boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ArtifactConfiguration.class)
                .withPropertyValues(
                        "data-agent.artifact.enabled=" + enabled,
                        "data-agent.artifact.root-directory=" + root.resolve("artifacts"));
    }
}
