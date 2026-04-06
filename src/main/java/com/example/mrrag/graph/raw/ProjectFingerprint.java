package com.example.mrrag.graph.raw;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Stable version string for {@link ProjectKey}: prefer git {@code HEAD}, else hash of build files.
 */
@Slf4j
public final class ProjectFingerprint {

    private static final List<String> BUILD_FILES = List.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
    );

    private ProjectFingerprint() {
    }

    public static String compute(Path projectRoot) {
        Path abs = projectRoot.toAbsolutePath().normalize();
        Optional<String> git = tryGitHead(abs);
        if (git.isPresent()) {
            return "git:" + git.get();
        }
        return "content:" + hashBuildFiles(abs);
    }

    private static Optional<String> tryGitHead(Path workTree) {
        try (Git git = Git.open(workTree.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head != null) {
                return Optional.of(head.getName());
            }
        } catch (IOException e) {
            log.debug("No git repo at {}: {}", workTree, e.getMessage());
        }
        return Optional.empty();
    }

    private static String hashBuildFiles(Path root) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : BUILD_FILES) {
                Path p = root.resolve(name);
                if (Files.isRegularFile(p)) {
                    md.update(name.getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(Files.readAllBytes(p));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "fallback:" + Integer.toHexString(root.toString().hashCode());
        }
    }
}
