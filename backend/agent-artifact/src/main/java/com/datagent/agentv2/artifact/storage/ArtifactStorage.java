package com.datagent.agentv2.artifact.storage;

import com.datagent.agentv2.artifact.domain.ArtifactType;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/** Artifact 文件系统访问的窄边界；调用方只能使用服务端生成的相对存储键。 */
public interface ArtifactStorage {

    StagedArtifact createStaging(String buildJobId, ArtifactType type) throws IOException;

    void write(StagedArtifact staged, InputStream input) throws IOException;

    StoredArtifact publish(String artifactId, StagedArtifact staged) throws IOException;

    ArtifactReadHandle openReady(String storageKey, long expectedSize, String expectedSha256)
            throws IOException;

    void deletePublished(StoredArtifact stored) throws IOException;

    int deleteStagingOlderThan(Instant cutoff) throws IOException;
}
