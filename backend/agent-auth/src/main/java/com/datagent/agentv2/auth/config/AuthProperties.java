package com.datagent.agentv2.auth.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("data-agent.auth")
@Getter
public class AuthProperties {

    private final Session session = new Session();
    private final Bootstrap bootstrap = new Bootstrap();

    @Getter
    @Setter
    public static class Session {
        private String cookieName = "DATA_AGENT_SESSION";
        private boolean secureCookie;
        private Duration lifetime = Duration.ofHours(8);
    }

    @Getter
    @Setter
    public static class Bootstrap {
        private boolean enabled = true;
        private String tenantId = "tenant_local";
        private String tenantName = "Local";
        private String userId = "user_admin";

        /** 本地管理员对应的租户成员关系业务标识。 */
        private String tenantMembershipId = "tenant_membership_admin";

        private String username = "admin";
        private String password = "admin";
    }
}
