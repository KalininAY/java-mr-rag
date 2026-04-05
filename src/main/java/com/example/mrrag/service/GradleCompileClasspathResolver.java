package com.example.mrrag.service;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * Resolves {@code main} {@code compileClasspath} for a Gradle module by running the wrapper
 * (or system {@code gradle}) with an init script that registers {@code mrragSpoonCompileClasspath}.
 */
@Slf4j
public final class GradleCompileClasspathResolver {

    private static final String INIT_RESOURCE = "gradle/mrrag-spoon-classpath.init.gradle";
    private static final int TIMEOUT_MINUTES = 5;

    private GradleCompileClasspathResolver() {
    }

    /**
     * @param moduleRoot directory that contains or is under a Gradle build (e.g. repo root or sub-module)
     * @return classpath entries for {@link spoon.compiler.Environment#setSourceClasspath(String[])}, or empty
     */
    public static Optional<String[]> tryResolve(Path moduleRoot) {
        Path abs = moduleRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            return Optional.empty();
        }
        Optional<Path> gradleRootOpt = findGradleRoot(abs);
        if (gradleRootOpt.isEmpty()) {
            log.debug("No Gradle project found above {}", abs);
            return Optional.empty();
        }
        Path gradleRoot = gradleRootOpt.get();

        Path initScript;
        try {
            initScript = materializeInitScript();
        } catch (IOException e) {
            log.warn("Cannot extract Gradle init script for Spoon classpath: {}", e.getMessage());
            return Optional.empty();
        }

        String subTask = gradleTaskPath(gradleRoot, abs);
        Optional<String> cpLine = runGradle(gradleRoot, gradleCommand(gradleRoot, initScript, subTask));
        if (cpLine.isEmpty() && !subTask.isEmpty()) {
            log.debug("Gradle task {} failed or missing; trying root project task", subTask);
            cpLine = runGradle(gradleRoot, gradleCommand(gradleRoot, initScript, ""));
        }
        if (cpLine.isEmpty()) {
            log.debug("Gradle returned no classpath for module {}", abs);
            return Optional.empty();
        }

        String[] entries = splitClasspath(cpLine.get());
        if (entries.length == 0) {
            return Optional.empty();
        }
        List<String> merged = new ArrayList<>(Arrays.asList(entries));
        appendIfDir(merged, abs.resolve("build/classes/java/main"));
        appendIfDir(merged, abs.resolve("build/classes/kotlin/main"));
        appendIfDir(merged, abs.resolve("out/production/classes"));
        return Optional.of(merged.toArray(new String[0]));
    }

    /**
     * Nearest ancestor of {@code start} that looks like a Gradle build root.
     */
    static Optional<Path> findGradleRoot(Path start) {
        Path abs = start.toAbsolutePath().normalize();
        Optional<Path> withWrapper = firstAncestorMatching(abs, GradleCompileClasspathResolver::hasGradleWrapper);
        if (withWrapper.isPresent()) {
            return withWrapper;
        }
        Optional<Path> withSettings = firstAncestorMatching(abs, GradleCompileClasspathResolver::hasSettingsGradle);
        if (withSettings.isPresent()) {
            return withSettings;
        }
        return firstAncestorMatching(abs, GradleCompileClasspathResolver::hasBuildGradle);
    }

    private static Optional<Path> firstAncestorMatching(Path start, java.util.function.Predicate<Path> pred) {
        Path cur = start;
        for (int depth = 0; depth < 32; depth++) {
            if (pred.test(cur)) {
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

    private static boolean hasGradleWrapper(Path dir) {
        return Files.isRegularFile(dir.resolve("gradlew"))
                || Files.isRegularFile(dir.resolve("gradlew.bat"));
    }

    private static boolean hasSettingsGradle(Path dir) {
        return Files.isRegularFile(dir.resolve("settings.gradle"))
                || Files.isRegularFile(dir.resolve("settings.gradle.kts"));
    }

    private static boolean hasBuildGradle(Path dir) {
        return Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    private static Path materializeInitScript() throws IOException {
        try (InputStream in = GradleCompileClasspathResolver.class.getClassLoader()
                .getResourceAsStream(INIT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + INIT_RESOURCE);
            }
            Path tmp = Files.createTempFile("mrrag-spoon", ".init.gradle");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /**
     * Gradle task path like {@code :services:api:mrragSpoonCompileClasspath}, or empty for root project.
     */
    static String gradleTaskPath(Path gradleRoot, Path moduleRoot) {
        Path gr = gradleRoot.toAbsolutePath().normalize();
        Path mod = moduleRoot.toAbsolutePath().normalize();
        if (!mod.startsWith(gr)) {
            return "";
        }
        if (mod.equals(gr)) {
            return "";
        }
        Path rel = gr.relativize(mod);
        if (rel.getNameCount() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            sb.append(':').append(part.toString());
        }
        sb.append(":mrragSpoonCompileClasspath");
        return sb.toString();
    }

    private static List<String> gradleCommand(Path gradleRoot, Path initScript, String taskPath) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path gradlew = win ? gradleRoot.resolve("gradlew.bat") : gradleRoot.resolve("gradlew");
        List<String> cmd = new ArrayList<>();
        if (Files.isRegularFile(gradlew)) {
            cmd.add(gradlew.toAbsolutePath().toString());
        } else {
            cmd.add("gradle");
            cmd.add("-p");
            cmd.add(gradleRoot.toAbsolutePath().toString());
        }
        cmd.add("--no-daemon");
        cmd.add("-q");
        cmd.add("--init-script");
        cmd.add(initScript.toAbsolutePath().toString());
        if (taskPath == null || taskPath.isEmpty()) {
            cmd.add("mrragSpoonCompileClasspath");
        } else {
            cmd.add(taskPath);
        }
        return cmd;
    }

    private static Optional<String> runGradle(Path gradleRoot, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gradleRoot.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = r.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Gradle classpath subprocess timed out");
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                log.debug("Gradle exited with {} for command {}\n{}", p.exitValue(), command, output);
                return Optional.empty();
            }
            return parseClasspathBlock(output);
        } catch (IOException e) {
            log.debug("Gradle subprocess IO error: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Gradle subprocess interrupted");
            return Optional.empty();
        }
    }

    static Optional<String> parseClasspathBlock(String output) {
        String start = "SPOON_CP_START";
        String end = "SPOON_CP_END";
        int a = output.indexOf(start);
        int b = output.indexOf(end);
        if (a < 0 || b < 0 || b <= a) {
            return Optional.empty();
        }
        String block = output.substring(a + start.length(), b).trim();
        if (block.isEmpty()) {
            return Optional.empty();
        }
        int nl = block.indexOf('\n');
        String line = nl >= 0 ? block.substring(0, nl).trim() : block;
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
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
