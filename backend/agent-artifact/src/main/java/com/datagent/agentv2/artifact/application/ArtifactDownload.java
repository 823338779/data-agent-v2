package com.datagent.agentv2.artifact.application;

import com.datagent.agentv2.artifact.storage.ArtifactReadHandle;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import java.io.IOException;
import java.io.OutputStream;

/** 通过会话归属校验后的下载句柄；存储键不会进入 HTTP JSON。 */
public record ArtifactDownload(
        String displayName,
        String mediaType,
        long sizeBytes,
        String sha256,
        String storageKey,
        ArtifactStorage storage) {

    public void copyTo(OutputStream output) throws IOException {
        try (ArtifactReadHandle handle = storage.openReady(storageKey, sizeBytes, sha256)) {
            handle.inputStream().transferTo(output);
        }
    }
}
