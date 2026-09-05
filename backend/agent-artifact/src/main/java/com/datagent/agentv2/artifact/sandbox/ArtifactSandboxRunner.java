package com.datagent.agentv2.artifact.sandbox;

/** Linux/Windows 文档沙箱实现共同遵循的进程内 SPI。 */
public interface ArtifactSandboxRunner {

    SandboxPlatform platform();

    SandboxProbe probe();

    SandboxRunResult run(SandboxRunRequest request) throws InterruptedException;

    void cancel(String buildJobId);
}
