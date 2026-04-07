package com.example.mrrag.graph.raw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Maven repository base URLs from Gradle settings/build scripts
 * ({@code repositories}, {@code mavenCentral()}, {@code url(...)}, Kotlin DSL).
 */
public final class GradleRepositoriesParser {

    /** Default when no explicit repos are found (Gradle often relies on implicit central). */
    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    private static final String GOOGLE = "https://dl.google.com/dl/android/maven2/";
    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2";

    private static final Pattern MAVEN_CENTRAL_CALL = Pattern.compile("mavenCentral\\s*\\(");
    private static final Pattern GOOGLE_CALL = Pattern.compile("google\\s*\\(");
    private static final Pattern PLUGIN_PORTAL_CALL = Pattern.compile("gradlePluginPortal\\s*\\(");
    private static final Pattern JCENTER_CALL = Pattern.compile("jcenter\\s*\\(");

    /** Groovy: {@code url 'https://...'} or {@code url "https://..."} */
    private static final Pattern URL_GROOVY = Pattern.compile(
            "url\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    /** Groovy: {@code url(...)} */
    private static final Pattern URL_PAREN = Pattern.compile(
            "url\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    /** Kotlin: {@code maven("https://...")} or {@code maven(url = "https://...")} */
    private static final Pattern MAVEN_KOTLIN = Pattern.compile(
            "maven\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.MULTILINE);
    private static final Pattern URL_KOTLIN_NAMED = Pattern.compile(
            "url\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.MULTILINE);

    private GradleRepositoriesParser() {
    }

    /**
     * Scans {@code settings.gradle*}, root {@code build.gradle*}, and (if different) the module's
     * {@code build.gradle*} for repository declarations.
     *
     * @param gradleRoot  directory returned by {@link GradleCompileClasspathResolver#findGradleRoot}
     * @param moduleRoot  Gradle module directory (often same as gradleRoot or a sub-project)
     */
    public static List<String> collect(Path gradleRoot, Path moduleRoot) {
        Set<String> out = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            files.add(gradleRoot.resolve(name));
        }
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            files.add(gradleRoot.resolve(name));
        }
        Path mod = moduleRoot.toAbsolutePath().normalize();
        Path gr = gradleRoot.toAbsolutePath().normalize();
        if (!mod.equals(gr)) {
            for (String name : List.of("build.gradle", "build.gradle.kts")) {
                files.add(mod.resolve(name));
            }
        }
        for (Path f : files) {
            if (Files.isRegularFile(f)) {
                parseFile(f, out);
            }
        }
        if (out.isEmpty()) {
            out.add(MAVEN_CENTRAL);
        }
        return List.copyOf(out);
    }

    private static void parseFile(Path f, Set<String> out) {
        String content;
        try {
            content = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        String lowerName = f.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean kotlin = lowerName.endsWith(".kts");

        if (MAVEN_CENTRAL_CALL.matcher(content).find()) {
            out.add(MAVEN_CENTRAL);
        }
        if (GOOGLE_CALL.matcher(content).find()) {
            out.add(GOOGLE);
        }
        if (PLUGIN_PORTAL_CALL.matcher(content).find()) {
            out.add(GRADLE_PLUGIN_PORTAL);
        }
        if (JCENTER_CALL.matcher(content).find()) {
            out.add(MAVEN_CENTRAL);
        }

        if (kotlin) {
            addUrlMatches(MAVEN_KOTLIN.matcher(content), out);
            addUrlMatches(URL_KOTLIN_NAMED.matcher(content), out);
            addUrlMatches(URL_PAREN.matcher(content), out);
        } else {
            addUrlMatches(URL_GROOVY.matcher(content), out);
            addUrlMatches(URL_PAREN.matcher(content), out);
        }
    }

    private static void addUrlMatches(Matcher m, Set<String> out) {
        while (m.find()) {
            String url = m.group(1).trim();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.add(normalizeRepoBase(url));
            }
        }
    }

    /**
     * Ensures trailing slash for Maven layout ({@code group/artifact/version/...}).
     */
    public static String normalizeRepoBase(String url) {
        String t = url.trim();
        if (t.endsWith("/")) {
            return t;
        }
        return t + "/";
    }
}
