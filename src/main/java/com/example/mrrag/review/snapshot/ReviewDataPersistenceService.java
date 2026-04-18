package com.example.mrrag.review.snapshot;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.graph.model.ProjectGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Saves and enriches review snapshots on disk.
 *
 * <h2>Snapshot layout</h2>
 * <pre>
 * {namespace}-{repo}-mr{mrIid}-{timestamp}/
 *   meta.json            — {@link ReviewSnapshotMeta}  (updated after each step)
 *   diffs.json           — List&lt;Diff&gt;
 *   source/              — copy of cloned source-branch project
 *   target/              — copy of cloned target-branch project
 *   graph-source.json    — serialised {@link ProjectGraph} for source  (added later)
 *   graph-target.json    — serialised {@link ProjectGraph} for target  (added later)
 * </pre>
 */
@Slf4j
@Service
public class ReviewDataPersistenceService {

    private static final DateTimeFormatter DIR_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path snapshotsDir;
    private final ObjectMapper mapper;

    public ReviewDataPersistenceService(
            @Value("${app.snapshots.dir:${app.workspace.dir}/snapshots}") String snapshotsDirStr) {
        this.snapshotsDir = Path.of(snapshotsDirStr);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // -----------------------------------------------------------------------
    // Phase 1 — save right after clone + diffs (before graph build)
    // -----------------------------------------------------------------------

    /**
     * Creates the snapshot directory, writes {@code meta.json} and {@code diffs.json},
     * copies {@code sourceRoot} → {@code source/} and {@code targetRoot} → {@code target/}.
     * Sets {@link SnapshotState#SOURCES_READY} in meta.
     *
     * @return path to the created snapshot directory
     */
    public Path saveSnapshot(
            ReviewRequest request,
            MergeRequest mr,
            List<Diff> diffs,
            Path sourceRoot,
            Path targetRoot) {

        String ts = LocalDateTime.now().format(DIR_TS);
        String safeNs = request.namespace().replace('/', '-');
        String dirName = safeNs + "-" + request.repo()
                + "-mr" + request.mrIid() + "-" + ts;
        Path snapshotDir = snapshotsDir.resolve(dirName);

        try {
            Files.createDirectories(snapshotDir);

            String sourceSha = resolveGitHead(sourceRoot);
            String targetSha = resolveGitHead(targetRoot);

            ReviewSnapshotMeta meta = new ReviewSnapshotMeta(
                    request.namespace(),
                    request.repo(),
                    request.mrIid(),
                    mr.getSourceBranch(),
                    mr.getTargetBranch(),
                    mr.getTitle(),
                    sourceSha,
                    targetSha,
                    "source",
                    "target",
                    SnapshotState.SOURCES_READY,
                    LocalDateTime.now()
            );
            writeMeta(snapshotDir, meta);

            mapper.writeValue(snapshotDir.resolve("diffs.json").toFile(), diffs);
            log.debug("diffs.json written: {} diffs", diffs.size());

            copyTree(sourceRoot, snapshotDir.resolve("source"));
            log.debug("source/ copied from {}", sourceRoot);
            copyTree(targetRoot, snapshotDir.resolve("target"));
            log.debug("target/ copied from {}", targetRoot);

            log.info("Snapshot saved [{}]: sourceSha={} targetSha={}",
                    snapshotDir.getFileName(), sourceSha, targetSha);
            return snapshotDir;

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save review snapshot", e);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — persist graphs after they are built
    // -----------------------------------------------------------------------

    /**
     * Serialises both {@link ProjectGraph}s into the snapshot directory and
     * updates {@code meta.json} to {@link SnapshotState#GRAPHS_READY}.
     */
    public void saveGraphs(Path snapshotDir,
                           ProjectGraph sourceGraph,
                           ProjectGraph targetGraph) {
        try {
            mapper.writeValue(snapshotDir.resolve("graph-source.json").toFile(), sourceGraph);
            mapper.writeValue(snapshotDir.resolve("graph-target.json").toFile(), targetGraph);
            log.debug("graph-source.json / graph-target.json written to {}", snapshotDir);

            // update state in meta
            ReviewSnapshotMeta old = mapper.readValue(
                    snapshotDir.resolve("meta.json").toFile(), ReviewSnapshotMeta.class);
            ReviewSnapshotMeta updated = new ReviewSnapshotMeta(
                    old.namespace(), old.repo(), old.mrIid(),
                    old.sourceBranch(), old.targetBranch(), old.mrTitle(),
                    old.sourceCommitSha(), old.targetCommitSha(),
                    old.sourceRelDir(), old.targetRelDir(),
                    SnapshotState.GRAPHS_READY,
                    old.createdAt()
            );
            writeMeta(snapshotDir, updated);
            log.info("Snapshot state → GRAPHS_READY: {}", snapshotDir.getFileName());

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save graphs to snapshot", e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void writeMeta(Path snapshotDir, ReviewSnapshotMeta meta) throws IOException {
        mapper.writeValue(snapshotDir.resolve("meta.json").toFile(), meta);
    }

    /**
     * Reads the git HEAD SHA from a local clone; returns {@code "unknown"} if not a git repo.
     */
    static String resolveGitHead(Path repoRoot) {
        try (Git git = Git.open(repoRoot.toAbsolutePath().toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : "unknown";
        } catch (IOException e) {
            log.debug("Cannot resolve HEAD at {}: {}", repoRoot, e.getMessage());
            return "unknown";
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
