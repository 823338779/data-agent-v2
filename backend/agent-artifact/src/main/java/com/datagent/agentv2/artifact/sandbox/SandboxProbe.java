package com.datagent.agentv2.artifact.sandbox;

import java.util.Objects;

/** 沙箱启动自检结果，只暴露稳定原因码，不携带命令、环境或凭据。 */
public record SandboxProbe(boolean healthy, String reasonCode) {

    public SandboxProbe {
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public static SandboxProbe ready() {
        return new SandboxProbe(true, "READY");
    }
}
