package com.datagent.agentv2.platform.context;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class TenantContext {

    /** 当前单租户运行模式使用的固定租户标识。 */
    public static final String TENANT_ID = "tenant_default";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static String requiredTenantId() {
        return Optional.ofNullable(CURRENT_TENANT.get()).orElse(TENANT_ID);
    }

    public static void run(String tenantId, Runnable action) {
        call(
                tenantId,
                () -> {
                    action.run();
                    return null;
                });
    }

    public static <T> T call(String tenantId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        String previous = CURRENT_TENANT.get();
        CURRENT_TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_TENANT.remove();
            } else {
                CURRENT_TENANT.set(previous);
            }
        }
    }
}
