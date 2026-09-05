package com.datagent.agentv2.artifact.storage;

import java.io.IOException;
import java.io.InputStream;

/** 完整性预检通过后的只读流句柄。 */
public record ArtifactReadHandle(InputStream inputStream, long sizeBytes, String sha256)
        implements AutoCloseable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
