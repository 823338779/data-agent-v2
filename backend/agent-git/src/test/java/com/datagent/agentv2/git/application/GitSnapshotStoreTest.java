package com.datagent.agentv2.git.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotStoreTest {
    @TempDir Path root;

    @Test
    void missingSnapshotRetainsDiagnosticCause() {
        var store =
                new GitSnapshotStore(
                        new GitProperties(
                                "git",
                                "unused",
                                root,
                                Duration.ofSeconds(1),
                                1024,
                                1024,
                                10,
                                10,
                                1024,
                                1024));
        var change = new GitChangeEntity();
        change.setSnapshotStorageKey("snapshots/missing.snapshot");
        assertThatThrownBy(() -> store.load(change))
                .isInstanceOf(GitException.class)
                .hasCauseInstanceOf(IOException.class);
    }
}
