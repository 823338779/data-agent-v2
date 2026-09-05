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

    /** 在响应提交前完成大小和摘要预检，避免完整性失败被误报为成功下载。 */
    public void validate() throws IOException {
        try (ArtifactReadHandle ignored = storage.openReady(storageKey, sizeBytes, sha256)) {
            // openReady 已完成全文件摘要校验；此处只关闭预检流。
        }
    }

    public void copyTo(OutputStream output) throws IOException {
        try (ArtifactReadHandle handle = storage.openReady(storageKey, sizeBytes, sha256)) {
            handle.inputStream().transferTo(output);
        }
    }
}
