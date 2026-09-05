package com.datagent.agentv2.artifact;

import com.datagent.agentv2.artifact.sandbox.ArtifactSandboxRunner;
import com.datagent.agentv2.artifact.sandbox.SandboxPlatform;
import com.datagent.agentv2.artifact.sandbox.SandboxProbe;
import com.datagent.agentv2.artifact.sandbox.SandboxRunRequest;
import com.datagent.agentv2.artifact.sandbox.SandboxRunResult;

/** 启动装配测试使用的无进程 Fake，不执行任何宿主机命令。 */
final class FakeArtifactSandboxRunner implements ArtifactSandboxRunner {

    private final SandboxPlatform platform;
    private final SandboxProbe probe;

    FakeArtifactSandboxRunner(SandboxPlatform platform, SandboxProbe probe) {
        this.platform = platform;
        this.probe = probe;
    }

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
        throw new UnsupportedOperationException("startup fake does not run builds");
    }

    @Override
    public void cancel(String buildJobId) {
        // Fake 没有子进程需要取消。
    }
}
