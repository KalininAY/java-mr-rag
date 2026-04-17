package com.example.mrrag.review.snapshot;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
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
 * Saves a full review snapshot to disk <em>before</em> the grouping pipeline runs.
 * <p>
 * Snapshot layout inside {@code ${app.snapshots.dir}}:
 * <pre>
 * {namespace}-mr{mrIid}-{timestamp}/
 *   meta.json      — {@link ReviewSnapshotMeta}
 *   diffs.json     — List&lt;Diff&gt;
 *   source/        — copy of the cloned source-branch project
 *   target/        — copy of the cloned target-branch project
 * </pre>
 */
@Slf4j
@Service
public class ReviewDataPersistenceService {

    private static final DateTimeFormatter DIR_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

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

    /**
     * Creates the snapshot directory, writes {@code meta.json} and {@code diffs.json},
     * then copies {@code sourceRoot} → {@code source/} and {@code targetRoot} → {@code target/}.
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
        String dirName = safeNs + "-" + request.repo() + "-mr" + request.mrIid() + "-" + ts;
        Path snapshotDir = snapshotsDir.resolve(dirName);

        try {
            Files.createDirectories(snapshotDir);

            // --- meta.json ---
            ReviewSnapshotMeta meta = new ReviewSnapshotMeta(
                    request.namespace(),
                    request.repo(),
                    request.mrIid(),
                    mr.getSourceBranch(),
                    mr.getTargetBranch(),
                    mr.getTitle(),
                    "source",
                    "target",
                    LocalDateTime.now()
            );
            mapper.writeValue(snapshotDir.resolve("meta.json").toFile(), meta);
            log.debug("Snapshot meta.json written: {}", snapshotDir.resolve("meta.json"));

            // --- diffs.json ---
            mapper.writeValue(snapshotDir.resolve("diffs.json").toFile(), diffs);
            log.debug("Snapshot diffs.json written: {} diffs", diffs.size());

            // --- source/ and target/ ---
            copyTree(sourceRoot, snapshotDir.resolve("source"));
            log.debug("Snapshot source/ copied from {}", sourceRoot);
            copyTree(targetRoot, snapshotDir.resolve("target"));
            log.debug("Snapshot target/ copied from {}", targetRoot);

            log.info("Review snapshot saved: {}", snapshotDir);
            return snapshotDir;

        } catch (IOException e) {
            log.error("Failed to save review snapshot to {}: {}", snapshotDir, e.getMessage(), e);
            throw new UncheckedIOException("Failed to save review snapshot", e);
        }
    }

    // -----------------------------------------------------------------------

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
