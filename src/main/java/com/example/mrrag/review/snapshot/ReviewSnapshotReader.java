package com.example.mrrag.review.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads review snapshots from disk for the debug controller.
 */
@Slf4j
@Service
public class ReviewSnapshotReader {

    private final Path snapshotsDir;
    private final ObjectMapper mapper;

    public ReviewSnapshotReader(
            @Value("${app.snapshots.dir:${app.workspace.dir}/snapshots}") String snapshotsDirStr) {
        this.snapshotsDir = Path.of(snapshotsDirStr);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Lists all snapshot directory names (sorted, newest first by name). */
    public List<String> listSnapshotIds() throws IOException {
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted((a, b) -> b.compareTo(a))   // newest first
                    .toList();
        }
    }

    /** Returns the root directory of a snapshot by its id (directory name). */
    public Path snapshotDir(String snapshotId) {
        return snapshotsDir.resolve(snapshotId);
    }

    /** Reads {@code meta.json} of the given snapshot. */
    public ReviewSnapshotMeta readMeta(String snapshotId) throws IOException {
        Path file = snapshotsDir.resolve(snapshotId).resolve("meta.json");
        assertExists(file, snapshotId);
        return mapper.readValue(file.toFile(), ReviewSnapshotMeta.class);
    }

    /** Reads {@code diffs.json} of the given snapshot. */
    public List<Diff> readDiffs(String snapshotId) throws IOException {
        Path file = snapshotsDir.resolve(snapshotId).resolve("diffs.json");
        assertExists(file, snapshotId);
        return mapper.readValue(file.toFile(), new TypeReference<List<Diff>>() {});
    }

    /** Returns absolute path to the {@code source/} project copy inside the snapshot. */
    public Path sourceRoot(String snapshotId) throws IOException {
        ReviewSnapshotMeta meta = readMeta(snapshotId);
        return snapshotsDir.resolve(snapshotId).resolve(meta.sourceRelDir());
    }

    /** Returns absolute path to the {@code target/} project copy inside the snapshot. */
    public Path targetRoot(String snapshotId) throws IOException {
        ReviewSnapshotMeta meta = readMeta(snapshotId);
        return snapshotsDir.resolve(snapshotId).resolve(meta.targetRelDir());
    }

    // -----------------------------------------------------------------------

    private static void assertExists(Path file, String snapshotId) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(
                    "Snapshot file not found: " + file + " (snapshotId='" + snapshotId + "')");
        }
    }
}
