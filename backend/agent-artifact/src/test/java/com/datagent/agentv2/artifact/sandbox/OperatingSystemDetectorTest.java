package com.datagent.agentv2.artifact.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OperatingSystemDetectorTest {

    @Test
    void recognizesSupportedPlatformsCaseInsensitively() {
        assertThat(new OperatingSystemDetector(() -> " Windows 11 ").detect())
                .isEqualTo(SandboxPlatform.WINDOWS);
        assertThat(new OperatingSystemDetector(() -> "LINUX").detect())
                .isEqualTo(SandboxPlatform.LINUX);
    }

    @Test
    void rejectsUnsupportedOperatingSystems() {
        assertThatThrownBy(() -> new OperatingSystemDetector(() -> "Mac OS X").detect())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported artifact sandbox operating system");
    }
}
