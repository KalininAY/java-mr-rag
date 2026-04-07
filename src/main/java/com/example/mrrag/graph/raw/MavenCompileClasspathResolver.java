package com.example.mrrag.graph.raw;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resolves compile-scope dependency classpath for a Maven module via
 * {@code maven-dependency-plugin:build-classpath}, then prepends {@code target/classes} if present.
 */
@Slf4j
public final class MavenCompileClasspathResolver {

    /**
     * Pinned plugin coordinate so the goal runs even when the POM does not declare the plugin.
     */
    private static final String DEP_PLUGIN_GOAL =
            "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath";

    private static final int TIMEOUT_MINUTES = 5;

    private MavenCompileClasspathResolver() {
    }

    /**
     * @param moduleRoot directory under a Maven module (nearest ancestor with {@code pom.xml} is used)
     * @return classpath entries for {@link spoon.compiler.Environment#setSourceClasspath(String[])}, or empty
     */
    public static Optional<String[]> tryResolve(Path moduleRoot) {
        Path abs = moduleRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            return Optional.empty();
        }
        Optional<Path> mavenModuleOpt = findMavenModuleRoot(abs);
        if (mavenModuleOpt.isEmpty()) {
            log.debug("No pom.xml found above {}", abs);
            return Optional.empty();
        }
        Path mavenModule = mavenModuleOpt.get();

        Path outputFile;
        try {
            outputFile = Files.createTempFile("mrrag-maven-cp", ".txt");
            outputFile.toFile().deleteOnExit();
        } catch (IOException e) {
            log.warn("Cannot create temp file for Maven classpath: {}", e.getMessage());
            return Optional.empty();
        }

        List<String> command = mavenCommand(mavenModule, outputFile);
        Optional<String> cpLine = runMaven(mavenModule, command, outputFile);
        if (cpLine.isEmpty()) {
            log.debug("Maven did not produce classpath for {}", mavenModule);
            return Optional.empty();
        }

        String[] entries = splitClasspath(cpLine.get());
        if (entries.length == 0) {
            return Optional.empty();
        }
        List<String> merged = new ArrayList<>(Arrays.asList(entries));
        appendIfDir(merged, mavenModule.resolve("target/classes"));
        return Optional.of(merged.toArray(new String[0]));
    }

    /**
     * Nearest ancestor of {@code start} that contains a {@code pom.xml} (the Maven module base directory).
     */
    public static Optional<Path> findMavenModuleRoot(Path start) {
        Path cur = start.toAbsolutePath().normalize();
        for (int depth = 0; depth < 32; depth++) {
            if (Files.isRegularFile(cur.resolve("pom.xml"))) {
                return Optional.of(cur);
            }
            Path parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        return Optional.empty();
    }

    private static List<String> mavenCommand(Path mavenModule, Path outputFile) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path mvnw = win ? mavenModule.resolve("mvnw.cmd") : mavenModule.resolve("mvnw");
        List<String> cmd = new ArrayList<>();
        if (Files.isRegularFile(mvnw)) {
            cmd.add(mvnw.toAbsolutePath().toString());
        } else {
            cmd.add("mvn");
        }
        cmd.add("-q");
        cmd.add("--batch-mode");
        cmd.add(DEP_PLUGIN_GOAL);
        cmd.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
        cmd.add("-DincludeScope=compile");
        return cmd;
    }

    private static Optional<String> runMaven(Path mavenModule, List<String> command, Path outputFile) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(mavenModule.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String logOut;
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                logOut = r.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Maven classpath subprocess timed out");
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                log.debug("Maven exited with {} for {}\n{}", p.exitValue(), command, logOut);
                return Optional.empty();
            }
            if (!Files.isRegularFile(outputFile)) {
                return Optional.empty();
            }
            String content = Files.readString(outputFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return Optional.empty();
            }
            int nl = content.indexOf('\n');
            String line = nl >= 0 ? content.substring(0, nl).trim() : content;
            return line.isEmpty() ? Optional.empty() : Optional.of(line);
        } catch (IOException e) {
            log.debug("Maven subprocess IO error: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Maven subprocess interrupted");
            return Optional.empty();
        }
    }

    private static String[] splitClasspath(String pathLine) {
        String[] parts = pathLine.split(File.pathSeparator);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    private static void appendIfDir(List<String> entries, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        String s = dir.toAbsolutePath().toString();
        for (String e : entries) {
            if (e.equals(s)) {
                return;
            }
        }
        entries.add(0, s);
    }
}
