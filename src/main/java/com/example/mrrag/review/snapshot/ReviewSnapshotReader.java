package com.example.mrrag.review.snapshot;

import com.example.mrrag.graph.model.ProjectGraph;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads review snapshots from disk.
 *
 * <p>The key method for the review pipeline is {@link #findMatchingSnapshot}:
 * it locates the most-recent snapshot for a given MR whose
 * {@code targetCommitSha} still matches the current HEAD of the target branch.
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

    // -----------------------------------------------------------------------
    // Snapshot lookup
    // -----------------------------------------------------------------------

    /**
     * Finds the most-recent snapshot for the given MR that is still valid
     * (i.e. {@code targetCommitSha} matches {@code currentTargetSha}).
     *
     * @param namespace       GitLab namespace
     * @param repo            repository name
     * @param mrIid           MR internal id
     * @param currentTargetSha current HEAD SHA of the target branch
     *                         (pass {@code null} or empty to skip SHA check)
     * @return matching snapshot directory, or empty if none found
     */
    public Optional<Path> findMatchingSnapshot(String namespace,
                                               String repo,
                                               long mrIid,
                                               String currentTargetSha) {
        if (!Files.isDirectory(snapshotsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(dir -> metaMatches(dir, namespace, repo, mrIid, currentTargetSha))
                    .max(Comparator.comparing(p -> p.getFileName().toString())); // newest by name
        } catch (IOException e) {
            log.warn("Cannot scan snapshots dir {}: {}", snapshotsDir, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Determines what data is available in the snapshot directory.
     * Does NOT read meta.json — inspects files directly so the result
     * reflects the real state on disk (meta.json state field may be stale).
     */
    public SnapshotState detectState(Path snapshotDir) {
        boolean hasGraphs = Files.exists(snapshotDir.resolve("graph-source.json"))
                && Files.exists(snapshotDir.resolve("graph-target.json"));
        boolean hasSources = Files.isDirectory(snapshotDir.resolve("source"))
                && Files.isDirectory(snapshotDir.resolve("target"));
        boolean hasDiffs = Files.exists(snapshotDir.resolve("diffs.json"));

        if (hasGraphs && hasSources && hasDiffs) return SnapshotState.GRAPHS_READY;
        if (hasSources && hasDiffs)              return SnapshotState.SOURCES_READY;
        if (hasDiffs)                            return SnapshotState.DIFFS_ONLY;
        return SnapshotState.EMPTY;
    }

    // -----------------------------------------------------------------------
    // Data readers
    // -----------------------------------------------------------------------

    /** Lists all snapshot directory names, newest first. */
    public List<String> listSnapshotIds() throws IOException {
        if (!Files.isDirectory(snapshotsDir)) return List.of();
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    public Path snapshotDir(String snapshotId) {
        return snapshotsDir.resolve(snapshotId);
    }

    public ReviewSnapshotMeta readMeta(String snapshotId) throws IOException {
        return readMetaFromDir(snapshotsDir.resolve(snapshotId));
    }

    public List<Diff> readDiffs(String snapshotId) throws IOException {
        Path file = snapshotsDir.resolve(snapshotId).resolve("diffs.json");
        assertExists(file, snapshotId);
        return mapper.readValue(file.toFile(), new TypeReference<>() {});
    }

    public List<Diff> readDiffsFromDir(Path snapshotDir) throws IOException {
        Path file = snapshotDir.resolve("diffs.json");
        assertExists(file, snapshotDir.toString());
        return mapper.readValue(file.toFile(), new TypeReference<>() {});
    }

    public ProjectGraph readSourceGraph(Path snapshotDir) throws IOException {
        Path file = snapshotDir.resolve("graph-source.json");
        assertExists(file, snapshotDir.toString());
        return mapper.readValue(file.toFile(), ProjectGraph.class);
    }

    public ProjectGraph readTargetGraph(Path snapshotDir) throws IOException {
        Path file = snapshotDir.resolve("graph-target.json");
        assertExists(file, snapshotDir.toString());
        return mapper.readValue(file.toFile(), ProjectGraph.class);
    }

    public Path sourceRoot(String snapshotId) throws IOException {
        ReviewSnapshotMeta meta = readMeta(snapshotId);
        return snapshotsDir.resolve(snapshotId).resolve(meta.sourceRelDir());
    }

    public Path targetRoot(String snapshotId) throws IOException {
        ReviewSnapshotMeta meta = readMeta(snapshotId);
        return snapshotsDir.resolve(snapshotId).resolve(meta.targetRelDir());
    }

    public Path sourceRootFromDir(Path snapshotDir) throws IOException {
        return snapshotDir.resolve(readMetaFromDir(snapshotDir).sourceRelDir());
    }

    public Path targetRootFromDir(Path snapshotDir) throws IOException {
        return snapshotDir.resolve(readMetaFromDir(snapshotDir).targetRelDir());
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private ReviewSnapshotMeta readMetaFromDir(Path dir) throws IOException {
        Path file = dir.resolve("meta.json");
        assertExists(file, dir.toString());
        return mapper.readValue(file.toFile(), ReviewSnapshotMeta.class);
    }

    private boolean metaMatches(Path dir,
                                String namespace, String repo, long mrIid,
                                String currentTargetSha) {
        try {
            ReviewSnapshotMeta meta = readMetaFromDir(dir);
            if (!namespace.equals(meta.namespace())) return false;
            if (!repo.equals(meta.repo()))           return false;
            if (!mrIid.equals(meta.mrIid()))         return false;
            // SHA check: skip if currentTargetSha is blank (force-match)
            if (currentTargetSha != null && !currentTargetSha.isBlank()
                    && !currentTargetSha.equals(meta.targetCommitSha())) {
                log.debug("Snapshot {} skipped: targetSha mismatch ({} vs {})",
                        dir.getFileName(), meta.targetCommitSha(), currentTargetSha);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.debug("Cannot read meta.json in {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private static void assertExists(Path file, String context) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(
                    "Snapshot file not found: " + file + " (context='" + context + "')");
        }
    }
}
