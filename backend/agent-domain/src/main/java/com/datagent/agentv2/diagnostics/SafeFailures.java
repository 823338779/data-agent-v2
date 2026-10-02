package com.datagent.agentv2.diagnostics;

/** 为日志保留异常类型、堆栈和因果关系，同时丢弃可能携带凭据或模型原文的异常消息。 */
public final class SafeFailures {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_SUPPRESSED = 4;

    private SafeFailures() {}

    /** 返回独立诊断副本；不把原始异常挂入日志异常链。 */
    public static Throwable sanitized(Throwable failure) {
        return copy(failure, 0);
    }

    private static Throwable copy(Throwable failure, int depth) {
        if (failure == null || depth >= MAX_DEPTH) {
            return null;
        }
        SafeDiagnosticException result = new SafeDiagnosticException(failure.getClass().getName());
        result.setStackTrace(failure.getStackTrace());
        result.initCause(copy(failure.getCause(), depth + 1));
        // 限制损坏或循环异常图的诊断开销，避免失败处理再次失控。
        Throwable[] suppressed = failure.getSuppressed();
        for (int i = 0; i < Math.min(suppressed.length, MAX_SUPPRESSED); i++) {
            Throwable safe = copy(suppressed[i], depth + 1);
            if (safe != null) {
                result.addSuppressed(safe);
            }
        }
        return result;
    }

    private static final class SafeDiagnosticException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private SafeDiagnosticException(String type) {
            super(type);
        }
    }
}
