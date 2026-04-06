package com.example.mrrag.service.graph.raw;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a compile classpath for Spoon (Gradle first, then Maven) and collects Maven repository
 * base URLs for {@code *-sources.jar} resolution.
 */
public final class ClasspathResolver {

    public record Result(String[] entries, String source, List<String> remoteRepositories) {
        public Result {
            remoteRepositories = remoteRepositories == null ? List.of() : List.copyOf(remoteRepositories);
        }
    }

    private ClasspathResolver() {
    }

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
