package com.datagent.agentv2.artifact.sandbox;

import org.springframework.beans.factory.InitializingBean;

/** 功能启用时在应用接受请求前完成沙箱自检。 */
public final class ArtifactStartupValidator implements InitializingBean {

    private final ArtifactSandboxSelector selector;

    public ArtifactStartupValidator(ArtifactSandboxSelector selector) {
        this.selector = selector;
    }

    @Override
    public void afterPropertiesSet() {
        selector.requireHealthyRunner();
    }
}
