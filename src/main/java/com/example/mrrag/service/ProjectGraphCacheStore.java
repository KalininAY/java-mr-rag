package com.example.mrrag.service;

import com.example.mrrag.config.GraphCacheProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists {@link AstGraphService.ProjectGraph} as JSON files on disk.
 *
 * <h3>Storage layout</h3>
 * <pre>
 * cacheDir/
 *   &lt;sha256(root)&gt;_&lt;sha256(fingerprint)&gt;/    ← project bundle
 *     main.json
 *     segments.json                            ← manifest: list of all segment IDs
 *
 *   deps/                                      ← GLOBAL shared dependency graphs
 *     dep/com.example_foo_1.0.0/               ← one dir per dep (segmentId after prefix)
 *       graph.json
 *       meta.json
 *     jar/&lt;sha256&gt;/                            ← unknown-coordinates dep
 *       graph.json
 *       meta.json
 * </pre>
 *
 * <p>Dependency segments (segmentId starting with {@code "dep/"} or {@code "jar/"}) are stored in
 * the global {@code depsDir} so that multiple projects sharing the same library version reuse a
 * single cached graph rather than duplicating it in every project bundle.
 *
 * <p>The {@code main} segment always lives inside the project bundle.
 *
 * <p>Legacy single-file {@code sha256_root_sha256_fp.json} at cache root is still readable.
 */
@Slf4j
@Component
public class ProjectGraphCacheStore {

    private static final String MANIFEST   = "segments.json";
    private static final String GRAPH_FILE = "graph.json";
    private static final String META_FILE  = "meta.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

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
     * Global directory for shared dependency segment subdirectories.
     * Defaults to {@code cacheDir/deps} when {@code app.graph.cache.depsDir} is blank.
     */
    private Path depsDir() {
        String d = properties.getDepsDir();
        if (d == null || d.isBlank()) {
            return cacheDir().resolve("deps");
        }
        return Path.of(d);
    }

    /** Directory containing {@code main.json} and {@code segments.json} for a project. */
    public Path bundleDir(ProjectKey key) {
        return cacheDir().resolve(bundleDirName(key));
    }

    String bundleDirName(ProjectKey key) {
        return sha256Hex(normalizePath(key.projectRoot()))
                + "_" + sha256Hex(key.fingerprint());
    }

    /**
     * Directory under {@code depsDir} that holds {@code graph.json} and {@code meta.json}
     * for a dep segment.
     *
     * <p>Example: segmentId {@code "dep/com.example_foo_1.0.0"} →
     * {@code depsDir/dep/com.example_foo_1.0.0/}
     */
    public Path depSegmentDir(String segmentId) {
        // segmentId = "dep/<flat-name>" or "jar/<sha256>"
        // Split on first '/' only to produce exactly two components: prefix + name
        int slash = segmentId.indexOf('/');
        String prefix = segmentId.substring(0, slash);   // "dep" or "jar"
        String name   = segmentId.substring(slash + 1);  // "com.example_foo_1.0.0" or sha256
        return depsDir().resolve(prefix).resolve(name);
    }

    /** {@code graph.json} file inside a dep segment directory. */
    public Path depGraphFile(String segmentId) {
        return depSegmentDir(segmentId).resolve(GRAPH_FILE);
    }

    /** {@code meta.json} file inside a dep segment directory. */
    public Path depMetaFile(String segmentId) {
        return depSegmentDir(segmentId).resolve(META_FILE);
    }

    /** For non-dep segments (i.e. {@code main}) inside a project bundle. */
    static Path localSegmentFile(Path bundleDir, String segmentId) {
        return bundleDir.resolve(segmentId + ".json");
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Loads all segment graphs listed in the project's {@code segments.json} manifest.
     * Dep segments are resolved from the global {@link #depsDir()};
     * main segment from the project bundle.
     * Falls back to the legacy single-file format if no manifest exists.
     */
    public Optional<Map<String, AstGraphService.ProjectGraph>> tryLoadAllSegments(ProjectKey key) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        Path bundle   = bundleDir(key);
        Path manifest = bundle.resolve(MANIFEST);
        if (Files.isRegularFile(manifest)) {
            try {
                SegmentManifest man = MAPPER.readValue(manifest.toFile(), SegmentManifest.class);
                Map<String, AstGraphService.ProjectGraph> out = new LinkedHashMap<>();
                for (String id : man.segments) {
                    Optional<AstGraphService.ProjectGraph> g = loadSegmentFile(key, id);
                    if (g.isEmpty()) {
                        log.warn("Missing segment file for '{}', treating cache as incomplete", id);
                        return Optional.empty();
                    }
                    out.put(id, g.get());
                }
                log.debug("Loaded {} segment(s) from bundle {}", out.size(), bundle);
                return Optional.of(out);
            } catch (IOException e) {
                log.warn("Failed to read segment bundle {}: {}", bundle, e.getMessage());
                return Optional.empty();
            }
        }
        // Legacy: single JSON file
        return tryLoadLegacySingleFile(key).map(g -> {
            Map<String, AstGraphService.ProjectGraph> m = new LinkedHashMap<>();
            m.put(GraphSegmentIds.MAIN, g);
            return m;
        });
    }

    /**
     * Loads one named segment from disk.
     * Dep segments are resolved from the global deps directory first;
     * falls back to the project bundle for backward compatibility with the old layout.
     */
    public Optional<AstGraphService.ProjectGraph> tryLoadSegment(ProjectKey key, String segmentId) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        return loadSegmentFile(key, segmentId);
    }

    private Optional<AstGraphService.ProjectGraph> loadSegmentFile(ProjectKey key, String segmentId) {
        if (GraphSegmentIds.isDepSegment(segmentId)) {
            // 1. Global deps directory
            Path globalGraph = depGraphFile(segmentId);
            if (Files.isRegularFile(globalGraph)) {
                return readGraphJson(globalGraph, segmentId);
            }
            // 2. Backward-compat: old flat layout inside project bundle
            Path legacyFile = bundleDir(key).resolve(segmentId.replace('/', '_') + ".json");
            if (Files.isRegularFile(legacyFile)) {
                log.debug("Dep segment '{}' found in project bundle (legacy), consider re-caching", segmentId);
                return readGraphJson(legacyFile, segmentId);
            }
            return Optional.empty();
        }
        // Non-dep (main)
        Path f = localSegmentFile(bundleDir(key), segmentId);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        return readGraphJson(f, segmentId);
    }

    private Optional<AstGraphService.ProjectGraph> readGraphJson(Path f, String segmentId) {
        try (InputStream in = Files.newInputStream(f)) {
            return Optional.of(ProjectGraphSerialization.read(in));
        } catch (IOException e) {
            log.warn("Failed to read segment '{}' from {}: {}", segmentId, f, e.getMessage());
            return Optional.empty();
        }
    }

    /** Legacy: single JSON at cache root (pre-sharded layout). */
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
            log.warn("Failed to read legacy cache {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * Saves all segments:
     * <ul>
     *   <li>Dep segments → {@code depsDir/<prefix>/<name>/graph.json} + {@code meta.json}.
     *       An existing dep directory is left untouched (first writer wins).
     *   <li>Main segment → project bundle ({@code main.json}).
     * </ul>
     * All writes are atomic (tmp file → move).
     */
    public void savePartitioned(ProjectKey key, Map<String, AstGraphService.ProjectGraph> segments) throws IOException {
        if (!properties.isSerializationEnabled() || segments == null || segments.isEmpty()) {
            return;
        }
        Path bundle = bundleDir(key);
        Files.createDirectories(bundle);

        List<String> ids = new ArrayList<>(segments.keySet());
        for (Map.Entry<String, AstGraphService.ProjectGraph> e : segments.entrySet()) {
            if (GraphSegmentIds.isDepSegment(e.getKey())) {
                saveDepSegmentGlobal(e.getKey(), e.getValue(), key);
            } else {
                saveLocalSegment(bundle, e.getKey(), e.getValue());
            }
        }

        // Manifest in project bundle
        SegmentManifest man = new SegmentManifest();
        man.segments = ids;
        atomicWrite(bundle, MANIFEST, tmp -> MAPPER.writeValue(tmp.toFile(), man));

        // Remove legacy single-file
        try { Files.deleteIfExists(cacheDir().resolve(legacyCacheFileName(key))); }
        catch (IOException ignored) {}

        log.debug("Saved {} segment(s) — bundle: {}, global deps: {}", segments.size(), bundle, depsDir());
    }

    /**
     * Writes a dep segment to the global deps directory:
     * {@code depsDir/<prefix>/<name>/graph.json} and {@code meta.json}.
     * If the directory already exists, the segment is considered up-to-date and skipped.
     *
     * @param key used only to extract the original jar path for {@code meta.json}
     */
    private void saveDepSegmentGlobal(String segId,
                                       AstGraphService.ProjectGraph graph,
                                       ProjectKey key) throws IOException {
        Path dir = depSegmentDir(segId);
        Path graphFile = dir.resolve(GRAPH_FILE);
        if (Files.isRegularFile(graphFile)) {
            log.debug("Dep segment '{}' already in global cache, skipping", segId);
            return;
        }
        Files.createDirectories(dir);

        // graph.json
        atomicWrite(dir, GRAPH_FILE, tmp -> {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                ProjectGraphSerialization.write(graph, out);
            }
        });

        // meta.json
        DepSegmentMeta meta = DepSegmentMeta.from(segId, key);
        atomicWrite(dir, META_FILE, tmp -> MAPPER.writeValue(tmp.toFile(), meta));

        log.debug("Saved dep segment '{}' to global cache: {}", segId, dir);
    }

    private void saveLocalSegment(Path bundle, String segId,
                                   AstGraphService.ProjectGraph graph) throws IOException {
        Path f = localSegmentFile(bundle, segId);
        atomicWrite(bundle, f.getFileName().toString(), tmp -> {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                ProjectGraphSerialization.write(graph, out);
            }
        });
    }

    // ------------------------------------------------------------------
    // Delete / Invalidate
    // ------------------------------------------------------------------

    /**
     * Deletes the project bundle directory for the given key.
     * Global dep directories are intentionally left intact so other projects
     * sharing the same library continue to benefit from the cache.
     * Use {@link #deleteDepSegment(String)} for explicit dep cache invalidation.
     */
    public void delete(ProjectKey key) {
        if (!properties.isSerializationEnabled()) return;
        try {
            deleteRecursively(bundleDir(key));
            Files.deleteIfExists(cacheDir().resolve(legacyCacheFileName(key)));
        } catch (IOException e) {
            log.debug("Could not delete bundle for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Deletes all project bundles whose root path matches {@code projectRoot}.
     * Global dep directories are NOT touched.
     */
    public void deleteAllForRoot(Path projectRoot) {
        if (!properties.isSerializationEnabled()) return;
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) return;
        String prefix = sha256Hex(normalizePath(projectRoot)) + "_";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return !"deps".equals(name)
                                && name.startsWith(prefix)
                                && (Files.isDirectory(p) || name.endsWith(".json"));
                    })
                    .forEach(p -> {
                        try {
                            if (Files.isDirectory(p)) deleteRecursively(p);
                            else Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("Could not delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list cache dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Removes the entire dep segment directory ({@code graph.json} + {@code meta.json})
     * from the global deps cache.
     *
     * @param segmentId e.g. {@code "dep/com.example_foo_1.0.0"}
     */
    public void deleteDepSegment(String segmentId) {
        if (!GraphSegmentIds.isDepSegment(segmentId)) {
            log.warn("deleteDepSegment: '{}' is not a dep segment id", segmentId);
            return;
        }
        Path dir = depSegmentDir(segmentId);
        try {
            if (Files.isDirectory(dir)) {
                deleteRecursively(dir);
                log.debug("Deleted dep segment directory: {}", dir);
            }
        } catch (IOException e) {
            log.debug("Could not delete dep segment {}: {}", dir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    interface IoWriter {
        void write(Path tmp) throws IOException;
    }

    /** Atomic write: write to tmp, then move to target. */
    private static void atomicWrite(Path dir, String fileName, IoWriter writer) throws IOException {
        Path tmp = Files.createTempFile(dir, ".tmp-", null);
        try {
            writer.write(tmp);
            Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

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

    // ------------------------------------------------------------------
    // JSON models
    // ------------------------------------------------------------------

    public static final class SegmentManifest {
        public List<String> segments;
    }

    /**
     * Human-readable metadata stored alongside {@code graph.json} in each dep segment directory.
     */
    public static final class DepSegmentMeta {
        public String  groupId;
        public String  artifactId;
        public String  version;
        /** Absolute path of the {@code *-sources.jar} that was parsed to produce this graph. */
        public String  jarPath;
        public String  savedAt;

        static DepSegmentMeta from(String segId, ProjectKey projectKey) {
            DepSegmentMeta m = new DepSegmentMeta();
            // segId = "dep/com.example_foo_1.0.0" or "jar/<sha256>"
            if (segId.startsWith(GraphSegmentIds.DEP_PREFIX)) {
                String flat = segId.substring(GraphSegmentIds.DEP_PREFIX.length());
                int last  = flat.lastIndexOf('_');
                int first = flat.indexOf('_');
                if (first > 0 && last > first) {
                    m.groupId    = flat.substring(0, first);
                    m.artifactId = flat.substring(first + 1, last);
                    m.version    = flat.substring(last + 1);
                }
            }
            m.savedAt = Instant.now().toString();
            return m;
        }
    }
}
