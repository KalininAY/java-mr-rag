package com.example.mrrag.app.source;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Identifies a built graph: normalized project root on disk plus a version fingerprint
 * (e.g. git HEAD or content hash of build files).
 */
public record ProjectKey(Path projectRoot, String fingerprint) {

    public ProjectKey {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(fingerprint, "fingerprint");
        projectRoot = projectRoot.toAbsolutePath().normalize();
    }
}
