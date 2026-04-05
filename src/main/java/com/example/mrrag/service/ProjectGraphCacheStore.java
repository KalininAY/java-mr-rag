package com.example.mrrag.service;

import com.example.mrrag.config.GraphCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists {@link AstGraphService.ProjectGraph} as one JSON per segment.
 *
 * <h3>Storage layout</h3>
 * <pre>
 * cacheDir/
 *   &lt;sha256(root)&gt;_&lt;sha256(fingerprint)&gt;/   ← project bundle
 *     main.json
 *     segments.json                           ← manifest listing all segment IDs
 *   deps/                                     ← GLOBAL shared dependency graphs
 *     dep/com/example/foo/1.0.0.json          ← dep segment (Maven GAV)
 *     jar/&lt;sha256(absPath)&gt;.json             ← dep segment (unknown coordinates)
 * </pre>
 *
 * <p>Dependency segments ({@code segmentId} starting with {@code "dep/"} or {@code "jar/"})
 * are stored in the global {@code depsDir} so that multiple projects sharing the same library
 * version reuse a single cached graph file rather than duplicating it inside every project bundle.
 *
 * <p>The {@code main} segment always lives inside the project bundle.
 *
 * <p>Legacy single-file {@code sha256_root_sha256_fp.json} at cache root is still readable.
 */
@Slf4j
@Component
public class ProjectGraphCacheStore {

    private static final String MANIFEST = "segments.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphCacheProperties properties;

    public ProjectGraphCacheStore(GraphCacheProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Directory helpers
    // ------------------------------------------------------------------

    private Path cacheDir() {
        String d = properties.getDir();
        if (d == null || d.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "mrrag-graph-cache");
        }
        return Path.of(d);
    }

    /**
     * Global directory for shared dependency segment files.
     * Defaults to {@code cacheDir/deps} when {@code app.graph.cache.depsDir} is blank.
     */
    private Path depsDir() {
        String d = properties.getDepsDir();
        if (d == null || d.isBlank()) {
            return cacheDir().resolve("deps");
        }
        return Path.of(d);
    }

    /** Returns true when a segmentId represents a shared dependency (not project sources). */
    private static boolean isDepSegment(String segmentId) {
        return segmentId.startsWith("dep/") || segmentId.startsWith("jar/");
    }

    /** Directory containing {@code main.json}, {@code segments.json}. */
    public Path bundleDir(ProjectKey key) {
        return cacheDir().resolve(bundleDirName(key));
    }

    String bundleDirName(ProjectKey key) {
        return sha256Hex(normalizePath(key.projectRoot()))
                + "_" + sha256Hex(key.fingerprint());
    }

    /**
     * Resolves the physical file for a segment.
     * <ul>
     *   <li>Dep segments  → {@code depsDir/<segmentId>.json} (e.g. {@code deps/dep/com/example/foo/1.0.0.json})
     *   <li>Main segment  → {@code bundleDir/main.json}
     * </ul>
     */
    public Path segmentFile(ProjectKey key, String segmentId) {
        if (isDepSegment(segmentId)) {
            return depSegmentFile(segmentId);
        }
        return localSegmentFile(bundleDir(key), segmentId);
    }

    /** Physical path for a dep segment inside the global deps directory. */
    Path depSegmentFile(String segmentId) {
        // segmentId looks like "dep/com/example/foo/1.0.0" or "jar/<sha256>"
        // We turn it into a flat path under depsDir: dep/com/example/foo/1.0.0.json
        String[] parts = segmentId.split("/");
        Path p = depsDir();
        for (int i = 0; i < parts.length - 1; i++) {
            p = p.resolve(parts[i]);
        }
        return p.resolve(parts[parts.length - 1] + ".json");
    }

    /** Physical path for a segment inside a project bundle directory (only for non-dep segments). */
    static Path localSegmentFile(Path bundleDir, String segmentId) {
        if (GraphSegmentIds.MAIN.equals(segmentId)) {
            return bundleDir.resolve("main.json");
        }
        String[] parts = segmentId.split("/");
        Path p = bundleDir;
        for (int i = 0; i < parts.length - 1; i++) {
            p = p.resolve(parts[i]);
        }
        return p.resolve(parts[parts.length - 1] + ".json");
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Loads all segment graphs from disk (manifest + files).
     * Dep segments are read from the global {@link #depsDir()};
     * main segment is read from the project bundle.
     * Falls back to legacy single-file format if no manifest found.
     */
    public Optional<Map<String, AstGraphService.ProjectGraph>> tryLoadAllSegments(ProjectKey key) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        Path bundle = bundleDir(key);
        Path manifest = bundle.resolve(MANIFEST);
        if (Files.isRegularFile(manifest)) {
            try {
                SegmentManifest man = MAPPER.readValue(manifest.toFile(), SegmentManifest.class);
                Map<String, AstGraphService.ProjectGraph> out = new LinkedHashMap<>();
                for (String id : man.segments) {
                    Optional<AstGraphService.ProjectGraph> g = loadSegmentFile(key, id);
                    if (g.isEmpty()) {
                        log.warn("Missing segment file for {}, cache incomplete", id);
                        return Optional.empty();
                    }
                    out.put(id, g.get());
                }
                log.debug("Loaded {} graph segments from bundle {}", out.size(), bundle);
                return Optional.of(out);
            } catch (IOException e) {
                log.warn("Failed to load segment bundle {}: {}", bundle, e.getMessage());
                return Optional.empty();
            }
        }
        Optional<AstGraphService.ProjectGraph> legacy = tryLoadLegacySingleFile(key);
        return legacy.map(g -> {
            Map<String, AstGraphService.ProjectGraph> m = new LinkedHashMap<>();
            m.put(GraphSegmentIds.MAIN, g);
            return m;
        });
    }

    /**
     * Loads one segment from disk.
     * Dep segments are looked up in the global {@link #depsDir()} first;
     * if not found there, falls back to the project bundle (backward compatibility).
     */
    public Optional<AstGraphService.ProjectGraph> tryLoadSegment(ProjectKey key, String segmentId) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        return loadSegmentFile(key, segmentId);
    }

    /** Internal: resolves file, checks existence, deserializes. */
    private Optional<AstGraphService.ProjectGraph> loadSegmentFile(ProjectKey key, String segmentId) {
        if (isDepSegment(segmentId)) {
            // Try global deps directory first
            Path globalFile = depSegmentFile(segmentId);
            if (Files.isRegularFile(globalFile)) {
                return readSegmentFile(globalFile, segmentId);
            }
            // Backward-compat: try inside project bundle (pre-global-deps-cache layout)
            Path localFile = localSegmentFile(bundleDir(key), segmentId);
            if (Files.isRegularFile(localFile)) {
                log.debug("Dep segment {} found only in project bundle (legacy layout)", segmentId);
                return readSegmentFile(localFile, segmentId);
            }
            return Optional.empty();
        }
        Path f = localSegmentFile(bundleDir(key), segmentId);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        return readSegmentFile(f, segmentId);
    }

    private Optional<AstGraphService.ProjectGraph> readSegmentFile(Path f, String segmentId) {
        try (InputStream in = Files.newInputStream(f)) {
            return Optional.of(ProjectGraphSerialization.read(in));
        } catch (IOException e) {
            log.warn("Failed to read segment {} from {}: {}", segmentId, f, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Legacy: one JSON file at cache root (pre-sharded layout).
     */
    public Optional<AstGraphService.ProjectGraph> tryLoadLegacySingleFile(ProjectKey key) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        Path file = cacheDir().resolve(legacyCacheFileName(key));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(ProjectGraphSerialization.read(in));
        } catch (IOException e) {
            log.warn("Failed to deserialize legacy graph cache {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * Saves all segments:
     * <ul>
     *   <li>Dep segments ({@code dep/...}, {@code jar/...}) → global {@link #depsDir()} so they can
     *       be shared across projects that depend on the same library version.
     *       A dep segment that already exists in the global dir is <em>not</em> overwritten
     *       (first writer wins — same GAV implies identical content).
     *   <li>Main segment and other local segments → project bundle directory.
     * </ul>
     * Writes are atomic (temp file → {@code Files.move} with {@code REPLACE_EXISTING}).
     */
    public void savePartitioned(ProjectKey key, Map<String, AstGraphService.ProjectGraph> segments) throws IOException {
        if (!properties.isSerializationEnabled() || segments == null || segments.isEmpty()) {
            return;
        }
        Path bundle = bundleDir(key);
        Files.createDirectories(bundle);

        List<String> ids = new ArrayList<>(segments.keySet());
        for (Map.Entry<String, AstGraphService.ProjectGraph> e : segments.entrySet()) {
            String segId = e.getKey();
            AstGraphService.ProjectGraph graph = e.getValue();
            if (isDepSegment(segId)) {
                saveDepSegmentGlobal(segId, graph);
            } else {
                saveLocalSegment(bundle, segId, graph);
            }
        }

        // Write manifest in the project bundle (lists all segment IDs, incl. dep ones)
        SegmentManifest man = new SegmentManifest();
        man.segments = ids;
        Path manPath = bundle.resolve(MANIFEST);
        Path manTmp = Files.createTempFile(bundle, "manifest-", ".tmp");
        MAPPER.writeValue(manTmp.toFile(), man);
        Files.move(manTmp, manPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Remove legacy single-file if present
        Path legacy = cacheDir().resolve(legacyCacheFileName(key));
        try {
            Files.deleteIfExists(legacy);
        } catch (IOException ignored) {
        }
        log.debug("Saved {} graph segments (bundle: {}, global deps: {})",
                segments.size(), bundle, depsDir());
    }

    /**
     * Writes a dep segment to the global deps directory.
     * If the file already exists it is left untouched (same GAV → same content).
     */
    private void saveDepSegmentGlobal(String segId, AstGraphService.ProjectGraph graph) throws IOException {
        Path target = depSegmentFile(segId);
        if (Files.isRegularFile(target)) {
            log.debug("Dep segment {} already cached globally at {}, skipping write", segId, target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "dep-", ".json.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ProjectGraphSerialization.write(graph, out);
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.debug("Saved dep segment {} to global cache: {}", segId, target);
    }

    /** Writes a non-dep segment (e.g. main) inside the project bundle directory. */
    private void saveLocalSegment(Path bundle, String segId, AstGraphService.ProjectGraph graph) throws IOException {
        Path f = localSegmentFile(bundle, segId);
        Files.createDirectories(f.getParent());
        Path tmp = Files.createTempFile(bundle, "seg-", ".json.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ProjectGraphSerialization.write(graph, out);
        }
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------
    // Delete / Invalidate
    // ------------------------------------------------------------------

    /**
     * Deletes the project bundle (main.json + segments.json) for the given key.
     * Global dep-segment files are intentionally NOT deleted here so that other
     * projects sharing the same library version continue to benefit from the cache.
     * Use {@link #deleteDepSegment(String)} to explicitly remove a specific dep graph.
     */
    public void delete(ProjectKey key) {
        if (!properties.isSerializationEnabled()) {
            return;
        }
        try {
            Path bundle = bundleDir(key);
            if (Files.isDirectory(bundle)) {
                deleteRecursively(bundle);
            }
            Files.deleteIfExists(cacheDir().resolve(legacyCacheFileName(key)));
        } catch (IOException e) {
            log.debug("Could not delete graph cache for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Deletes all project bundles whose root matches {@code projectRoot}.
     * Global dep-segment files are NOT deleted.
     */
    public void deleteAllForRoot(Path projectRoot) {
        if (!properties.isSerializationEnabled()) {
            return;
        }
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = sha256Hex(normalizePath(projectRoot)) + "_";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                // Skip the global deps directory
                return !"deps".equals(name)
                        && name.startsWith(prefix)
                        && (name.endsWith(".json") || Files.isDirectory(p));
            }).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        deleteRecursively(p);
                    } else {
                        Files.deleteIfExists(p);
                    }
                    log.debug("Deleted graph cache entry: {}", p);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Could not list cache dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Explicitly removes a specific dependency segment from the global deps cache.
     * Use this when you know a library version has changed and its cached graph is stale.
     *
     * @param segmentId the dep segment id, e.g. {@code "dep/com/example/foo/1.0.0"}
     */
    public void deleteDepSegment(String segmentId) {
        if (!isDepSegment(segmentId)) {
            log.warn("deleteDepSegment called with non-dep segmentId: {}", segmentId);
            return;
        }
        Path f = depSegmentFile(segmentId);
        try {
            if (Files.deleteIfExists(f)) {
                log.debug("Deleted global dep segment: {}", f);
            }
        } catch (IOException e) {
            log.debug("Could not delete dep segment {}: {}", f, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Misc helpers
    // ------------------------------------------------------------------

    String legacyCacheFileName(ProjectKey key) {
        return bundleDirName(key) + ".json";
    }

    private static void deleteRecursively(Path root) throws IOException {
        FileSystemUtils.deleteRecursively(root);
    }

    private static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class SegmentManifest {
        public List<String> segments;
    }
}
