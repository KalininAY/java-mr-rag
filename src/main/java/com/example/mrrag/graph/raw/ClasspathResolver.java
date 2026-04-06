package com.example.mrrag.graph.raw;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a compile classpath for Spoon (Gradle first, then Maven) and collects Maven repository
 * base URLs from {@code build.gradle*}, {@code settings.gradle*}, or {@code pom.xml} for
 * {@code *-sources.jar} resolution.
 */
public final class ClasspathResolver {

    /**
     * @param entries        compile classpath for Spoon {@link spoon.compiler.Environment#setSourceClasspath}
     * @param source         {@code Gradle} or {@code Maven}
     * @param remoteRepositories Maven base URLs (trailing slash), used to download {@code -sources.jar}
     */
    public record Result(String[] entries, String source, List<String> remoteRepositories) {
        public Result {
            remoteRepositories = remoteRepositories == null ? List.of() : List.copyOf(remoteRepositories);
        }
    }

    private ClasspathResolver() {
    }

    /**
     * @return non-empty classpath and metadata, or empty
     */
    public static Optional<Result> tryResolve(Path projectRoot) {
        Path abs = projectRoot.toAbsolutePath().normalize();

        Optional<String[]> gradleCp = GradleCompileClasspathResolver.tryResolve(projectRoot);
        if (gradleCp.isPresent() && gradleCp.get().length > 0) {
            Optional<Path> gradleRoot = GradleCompileClasspathResolver.findGradleRoot(abs);
            List<String> repos = gradleRoot
                    .map(gr -> GradleRepositoriesParser.collect(gr, abs))
                    .orElseGet(() -> List.of(GradleRepositoriesParser.MAVEN_CENTRAL));
            return Optional.of(new Result(gradleCp.get(), "Gradle", repos));
        }

        Optional<String[]> mavenCp = MavenCompileClasspathResolver.tryResolve(projectRoot);
        if (mavenCp.isPresent() && mavenCp.get().length > 0) {
            Optional<Path> mavenModule = MavenCompileClasspathResolver.findMavenModuleRoot(abs);
            List<String> repos = mavenModule
                    .map(MavenRepositoriesParser::collect)
                    .orElseGet(() -> List.of(GradleRepositoriesParser.MAVEN_CENTRAL));
            return Optional.of(new Result(mavenCp.get(), "Maven", repos));
        }

        return Optional.empty();
    }
}
