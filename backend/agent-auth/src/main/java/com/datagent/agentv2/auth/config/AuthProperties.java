package com.datagent.agentv2.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("data-agent.auth")
public class AuthProperties {

    private final Session session = new Session();
    private final Bootstrap bootstrap = new Bootstrap();

    public Session getSession() {
        return session;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public static class Session {
        private String cookieName = "DATA_AGENT_SESSION";
        private boolean secureCookie;
        private Duration lifetime = Duration.ofHours(8);

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public boolean isSecureCookie() {
            return secureCookie;
        }

        public void setSecureCookie(boolean secureCookie) {
            this.secureCookie = secureCookie;
        }

        public Duration getLifetime() {
            return lifetime;
        }

        public void setLifetime(Duration lifetime) {
            this.lifetime = lifetime;
        }
    }

    public static class Bootstrap {
        private boolean enabled = true;
        private String tenantId = "tenant_local";
        private String tenantName = "Local";
        private String userId = "user_admin";
        private String membershipId = "membership_admin";
        private String username = "admin";
        private String password = "admin";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getTenantName() {
            return tenantName;
        }

        public void setTenantName(String tenantName) {
            this.tenantName = tenantName;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getMembershipId() {
            return membershipId;
        }

        public void setMembershipId(String membershipId) {
            this.membershipId = membershipId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
