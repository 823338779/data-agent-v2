package com.datagent.agentv2.artifact.storage;

import com.datagent.agentv2.artifact.config.ArtifactProperties;
import com.datagent.agentv2.artifact.domain.ArtifactType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** 本机 Artifact 存储；路径只能由受校验业务 ID 和固定文件名组成。 */
public final class LocalArtifactStorage implements ArtifactStorage {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,80}$");
    private static final int BUFFER_SIZE = 8192;

    private final Path root;
    private final long maxOutputBytes;

    public LocalArtifactStorage(ArtifactProperties properties) throws IOException {
        this.root = properties.getRootDirectory().toAbsolutePath().normalize();
        this.maxOutputBytes = properties.getMaxOutputBytes();
        Files.createDirectories(root);
        rejectSymlink(root);
        Files.createDirectories(root.resolve("staging"));
        Files.createDirectories(root.resolve("ready"));
    }

    @Override
    public StagedArtifact createStaging(String buildJobId, ArtifactType type) throws IOException {
        requireSafeId(buildJobId, "buildJobId");
        Objects.requireNonNull(type, "type");
        String storageKey = "staging/" + buildJobId + "/output." + type.extension();
        Path file = resolve(storageKey);
        Files.createDirectory(file.getParent());
        return new StagedArtifact(buildJobId, type, storageKey);
    }

    @Override
    public void write(StagedArtifact staged, InputStream input) throws IOException {
        Path target = resolve(staged.storageKey());
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxOutputBytes) {
                    throw new IOException("artifact output exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    @Override
    public StoredArtifact publish(String artifactId, StagedArtifact staged) throws IOException {
        requireSafeId(artifactId, "artifactId");
        Path stagedFile = resolve(staged.storageKey());
        if (!Files.isRegularFile(stagedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staged artifact is missing");
        }
        Path stagingDirectory = stagedFile.getParent();
        Path readyDirectory = resolve("ready/" + artifactId);
        try {
            Files.move(stagingDirectory, readyDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("atomic artifact publication is unavailable", failure);
        }
        String storageKey = "ready/" + artifactId + "/output." + staged.artifactType().extension();
        Path published = resolve(storageKey);
        Digest digest = digest(published);
        return new StoredArtifact(
                artifactId, staged.artifactType(), storageKey, digest.sizeBytes(), digest.sha256());
    }

    @Override
    public ArtifactReadHandle openReady(String storageKey, long expectedSize, String expectedSha256)
            throws IOException {
        if (!storageKey.matches("^ready/[A-Za-z0-9_-]{1,80}/output\\.(docx|pptx|xlsx)$")) {
            throw new IOException("invalid ready artifact storage key");
        }
        Path file = resolve(storageKey);
        Digest actual = digest(file);
        if (actual.sizeBytes() != expectedSize || !actual.sha256().equals(expectedSha256)) {
            throw new IOException("artifact integrity check failed");
        }
        return new ArtifactReadHandle(
                Files.newInputStream(file), actual.sizeBytes(), actual.sha256());
    }

    @Override
    public void deletePublished(StoredArtifact stored) throws IOException {
        Path file = resolve(stored.storageKey());
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent());
    }

    @Override
    public int deleteStagingOlderThan(Instant cutoff) throws IOException {
        int deleted = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(resolve("staging"))) {
            for (Path directory : directories) {
                rejectSymlink(directory);
                FileTime modified = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS);
                if (modified.toInstant().isBefore(cutoff)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                        for (Path file : files) {
                            rejectSymlink(file);
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.delete(directory);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private Path resolve(String storageKey) throws IOException {
        Path relative = Path.of(storageKey);
        if (relative.isAbsolute()) {
            throw new IOException("absolute artifact storage key is forbidden");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("artifact storage key escapes configured root");
        }
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlink(current);
            }
        }
        return candidate;
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("symbolic links are forbidden in artifact storage");
        }
    }

    private static void requireSafeId(String value, String name) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a safe storage identifier");
        }
    }

    private static Digest digest(Path file) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                sha256.update(buffer, 0, read);
                size += read;
            }
        }
        return new Digest(size, HexFormat.of().formatHex(sha256.digest()));
    }

    private record Digest(long sizeBytes, String sha256) {}
}
