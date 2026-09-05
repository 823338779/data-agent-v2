package com.datagent.agentv2.artifact.sandbox;

import java.util.Locale;
import java.util.function.Supplier;

/** 将 JVM 操作系统名称严格映射为已审计的 Linux 或 Windows 沙箱平台。 */
public final class OperatingSystemDetector {

    private final Supplier<String> osName;

    public OperatingSystemDetector() {
        this(() -> System.getProperty("os.name", ""));
    }

    OperatingSystemDetector(Supplier<String> osName) {
        this.osName = osName;
    }

    public SandboxPlatform detect() {
        String normalized = osName.get().strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("windows")) {
            return SandboxPlatform.WINDOWS;
        }
        if (normalized.equals("linux") || normalized.startsWith("linux ")) {
            return SandboxPlatform.LINUX;
        }
        throw new IllegalStateException("Unsupported artifact sandbox operating system");
    }
}
