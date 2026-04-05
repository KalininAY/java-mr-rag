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
 * Persists {@link AstGraphService.ProjectGraph} as one JSON per segment under
 * {@code cacheDir / (sha256(root) _ sha256(fingerprint)) /}:
 * {@code main.json}, {@code dep/.../....json}, plus {@code segments.json} manifest.
 * Legacy single-file {@code sha256_root_sha256_fp.json} at cache root is still read.
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

    private Path cacheDir() {
        String d = properties.getDir();
        if (d == null || d.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "mrrag-graph-cache");
        }
        return Path.of(d);
    }

    /** Directory containing {@code main.json}, {@code segments.json}, and dependency shards. */
    public Path bundleDir(ProjectKey key) {
        return cacheDir().resolve(bundleDirName(key));
    }

    String bundleDirName(ProjectKey key) {
        return sha256Hex(normalizePath(key.projectRoot()))
                + "_" + sha256Hex(key.fingerprint());
    }

    /**
     * Loads all segment graphs from disk (manifest + files), or wraps legacy single-file graph as {@link GraphSegmentIds#MAIN}.
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
                    Path f = segmentFile(bundle, id);
                    if (!Files.isRegularFile(f)) {
                        log.warn("Missing segment file for {}: {}", id, f);
                        return Optional.empty();
                    }
                    try (InputStream in = Files.newInputStream(f)) {
                        out.put(id, ProjectGraphSerialization.read(in));
                    }
                }
                log.debug("Loaded {} graph segments from {}", out.size(), bundle);
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
     * Loads one segment JSON (no manifest required if file exists).
     */
    public Optional<AstGraphService.ProjectGraph> tryLoadSegment(ProjectKey key, String segmentId) {
        if (!properties.isSerializationEnabled()) {
            return Optional.empty();
        }
        Path f = segmentFile(bundleDir(key), segmentId);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(f)) {
            return Optional.of(ProjectGraphSerialization.read(in));
        } catch (IOException e) {
            log.warn("Failed to load segment {}: {}", f, e.getMessage());
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

    public void savePartitioned(ProjectKey key, Map<String, AstGraphService.ProjectGraph> segments) throws IOException {
        if (!properties.isSerializationEnabled() || segments == null || segments.isEmpty()) {
            return;
        }
        Path bundle = bundleDir(key);
        Files.createDirectories(bundle);
        List<String> ids = new ArrayList<>(segments.keySet());
        for (Map.Entry<String, AstGraphService.ProjectGraph> e : segments.entrySet()) {
            Path f = segmentFile(bundle, e.getKey());
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(bundle, "seg-", ".json.tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                ProjectGraphSerialization.write(e.getValue(), out);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        SegmentManifest man = new SegmentManifest();
        man.segments = ids;
        Path manPath = bundle.resolve(MANIFEST);
        Path manTmp = Files.createTempFile(bundle, "manifest-", ".tmp");
        MAPPER.writeValue(manTmp.toFile(), man);
        Files.move(manTmp, manPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path legacy = cacheDir().resolve(legacyCacheFileName(key));
        try {
            Files.deleteIfExists(legacy);
        } catch (IOException ignored) {
        }
        log.debug("Saved {} graph segments under {}", segments.size(), bundle);
    }

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
                return name.startsWith(prefix) && (name.endsWith(".json") || Files.isDirectory(p));
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

    static Path segmentFile(Path bundleDir, String segmentId) {
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
