package com.example.mrrag.graph.raw;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Downloads {@code -sources.jar} from Maven repository base URLs when not present locally.
 * Cached files use Maven repository layout under {@code cacheDir}:
 * {@code groupId/.../artifactId/version/artifactId-version-sources.jar}.
 */
@Slf4j
public final class RemoteSourcesJarResolver {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private RemoteSourcesJarResolver() {
    }

    /**
     * Tries each repository base URL in order until HTTP 200; stores under {@code cacheDir}
     * using Maven-style directories (see class javadoc).
     */
    public static Optional<Path> download(MavenArtifactCoordinates gav,
                                          List<String> repositoryBases,
                                          Path cacheDir) {
        if (repositoryBases == null || repositoryBases.isEmpty()) {
            return Optional.empty();
        }
        Path target = cachePath(cacheDir, gav);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            log.debug("Cannot create sources cache dirs for {}: {}", target, e.getMessage());
            return Optional.empty();
        }
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return Optional.of(target);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        String rel = gav.relativePath();
        for (String base : repositoryBases) {
            if (base == null || base.isBlank()) {
                continue;
            }
            String b = GradleRepositoriesParser.normalizeRepoBase(base.trim());
            String url = b + rel;
            if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
                continue;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", "java-mr-rag/RemoteSourcesJarResolver")
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200 && resp.body().length > 0) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, resp.body());
                    log.debug("Downloaded sources jar for {}:{}:{} from {}", gav.groupId(), gav.artifactId(),
                            gav.version(), url);
                    return Optional.of(target);
                }
            } catch (IOException | InterruptedException e) {
                log.debug("Sources jar not at {}: {}", url, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Path under {@code cacheDir} mirroring Maven repo layout, with sanitized path segments.
     */
    static Path cachePath(Path cacheDir, MavenArtifactCoordinates gav) {
        Path p = cacheDir;
        for (String seg : gav.groupId().split("\\.", -1)) {
            if (seg.isEmpty()) {
                continue;
            }
            p = p.resolve(sanitizePathSegment(seg));
        }
        String artifact = sanitizePathSegment(gav.artifactId());
        String version = sanitizePathSegment(gav.version());
        p = p.resolve(artifact).resolve(version);
        return p.resolve(artifact + "-" + version + "-sources.jar").normalize();
    }

    /**
     * Replaces characters that are invalid in file/path names on common filesystems (e.g. Windows).
     */
    static String sanitizePathSegment(String s) {
        if (s == null || s.isBlank()) {
            return "_";
        }
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
