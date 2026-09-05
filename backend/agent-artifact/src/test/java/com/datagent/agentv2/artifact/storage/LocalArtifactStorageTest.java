package com.datagent.agentv2.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.artifact.config.ArtifactProperties;
import com.datagent.agentv2.artifact.domain.ArtifactType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactStorageTest {

    @TempDir Path root;

    @Test
    void publishesStagingDirectoryWithoutExposingAnAbsolutePath() throws IOException {
        LocalArtifactStorage storage = storage(1024);
        byte[] content = "office-fixture".getBytes(StandardCharsets.UTF_8);
        StagedArtifact staged = storage.createStaging("job-1", ArtifactType.DOCX);
        storage.write(staged, new ByteArrayInputStream(content));

        StoredArtifact stored = storage.publish("artifact-1", staged);

        assertThat(stored.storageKey()).isEqualTo("ready/artifact-1/output.docx");
        assertThat(stored.storageKey()).doesNotContain(root.toString());
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        try (ArtifactReadHandle handle =
                storage.openReady(stored.storageKey(), stored.sizeBytes(), stored.sha256())) {
            assertThat(handle.inputStream().readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void rejectsTraversalOversizedOutputAndOverwrite() throws IOException {
        LocalArtifactStorage storage = storage(4);
        assertThatThrownBy(() -> storage.createStaging("../escape", ArtifactType.DOCX))
                .isInstanceOf(IllegalArgumentException.class);

        StagedArtifact oversized = storage.createStaging("job-big", ArtifactType.XLSX);
        assertThatThrownBy(
                        () ->
                                storage.write(
                                        oversized,
                                        new ByteArrayInputStream(
                                                "12345".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");

        LocalArtifactStorage normal = storage(1024);
        StagedArtifact first = normal.createStaging("job-a", ArtifactType.PPTX);
        normal.write(first, new ByteArrayInputStream(new byte[] {1}));
        normal.publish("artifact-a", first);
        StagedArtifact second = normal.createStaging("job-b", ArtifactType.PPTX);
        normal.write(second, new ByteArrayInputStream(new byte[] {2}));
        assertThatThrownBy(() -> normal.publish("artifact-a", second))
                .isInstanceOf(IOException.class);
    }

    @Test
    void detectsIntegrityMismatchBeforeReturningAReadHandle() throws IOException {
        LocalArtifactStorage storage = storage(1024);
        StagedArtifact staged = storage.createStaging("job-sha", ArtifactType.DOCX);
        storage.write(staged, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        StoredArtifact stored = storage.publish("artifact-sha", staged);

        assertThatThrownBy(
                        () ->
                                storage.openReady(
                                        stored.storageKey(), stored.sizeBytes(), "0".repeat(64)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");
    }

    private LocalArtifactStorage storage(long maxOutputBytes) throws IOException {
        ArtifactProperties properties = new ArtifactProperties();
        properties.setRootDirectory(root);
        properties.setMaxOutputBytes(maxOutputBytes);
        return new LocalArtifactStorage(properties);
    }
}
