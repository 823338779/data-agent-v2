package com.datagent.agentv2.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class SafeFailuresTest {
    @Test
    void retainsDiagnosticLocationsWithoutOriginalMessages() {
        var original =
                new IllegalStateException(
                        "password=private-value",
                        new IllegalArgumentException("token=another-private-value"));
        original.addSuppressed(new UnsupportedOperationException("sensitive-body"));
        Throwable safe = SafeFailures.sanitized(original);
        var output = new StringWriter();
        safe.printStackTrace(new PrintWriter(output));
        String diagnostic = output.toString();
        assertTrue(diagnostic.contains("IllegalStateException"));
        assertTrue(diagnostic.contains("IllegalArgumentException"));
        assertTrue(diagnostic.contains("UnsupportedOperationException"));
        assertTrue(diagnostic.contains("retainsDiagnosticLocationsWithoutOriginalMessages"));
        assertFalse(diagnostic.contains("private-value"));
        assertFalse(diagnostic.contains("sensitive-body"));
        assertNotSame(original, safe);
    }
}
