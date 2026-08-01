package com.datagent.agentv2.platform.context;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class OperatorContext {

    private static final ThreadLocal<OperatorIdentity> CURRENT_OPERATOR = new ThreadLocal<>();

    private OperatorContext() {}

    public static OperatorIdentity requiredOperator() {
        return Optional.ofNullable(CURRENT_OPERATOR.get())
                .orElseThrow(() -> new IllegalStateException("operator context is required"));
    }

    public static void run(OperatorIdentity operator, Runnable action) {
        call(
                operator,
                () -> {
                    action.run();
                    return null;
                });
    }

    public static <T> T call(OperatorIdentity operator, Supplier<T> action) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(action, "action");
        OperatorIdentity previous = CURRENT_OPERATOR.get();
        CURRENT_OPERATOR.set(operator);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_OPERATOR.remove();
            } else {
                CURRENT_OPERATOR.set(previous);
            }
        }
    }
}
