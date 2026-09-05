package com.datagent.agentv2.artifact.config;

import com.datagent.agentv2.artifact.sandbox.ArtifactSandboxRunner;
import com.datagent.agentv2.artifact.sandbox.ArtifactSandboxSelector;
import com.datagent.agentv2.artifact.sandbox.ArtifactStartupValidator;
import com.datagent.agentv2.artifact.sandbox.OperatingSystemDetector;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.artifact.storage.LocalArtifactStorage;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Artifact 基础设施装配；能力关闭时不要求宿主机具备沙箱运行时。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArtifactProperties.class)
public class ArtifactConfiguration {

    @Bean
    OperatingSystemDetector operatingSystemDetector() {
        return new OperatingSystemDetector();
    }

    @Bean
    ArtifactSandboxSelector artifactSandboxSelector(
            OperatingSystemDetector detector, List<ArtifactSandboxRunner> runners) {
        return new ArtifactSandboxSelector(detector, runners);
    }

    @Bean
    ArtifactStorage artifactStorage(ArtifactProperties properties) throws IOException {
        return new LocalArtifactStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-agent.artifact", name = "enabled", havingValue = "true")
    ArtifactStartupValidator artifactStartupValidator(ArtifactSandboxSelector selector) {
        return new ArtifactStartupValidator(selector);
    }
}
