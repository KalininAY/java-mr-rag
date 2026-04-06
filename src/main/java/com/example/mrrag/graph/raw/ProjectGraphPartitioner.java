package com.example.mrrag.graph.raw;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * Splits a full {@link ProjectGraph} into {@link GraphSegmentIds#MAIN} and one graph
 * per {@code *-sources.jar} by classifying {@link ProjectGraph.GraphNode#filePath()} and edge paths.
 */
public final class ProjectGraphPartitioner {

    private ProjectGraphPartitioner() {
    }

    public static Map<String, ProjectGraph> partition(
            ProjectGraph full,
            Path projectRoot,
            List<String> sourcesJarAbsPaths) {

        String rootNorm = projectRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<Path> jars = new ArrayList<>();
        for (String p : sourcesJarAbsPaths) {
            if (p != null && !p.isBlank()) {
                jars.add(Path.of(p.trim()).toAbsolutePath().normalize());
            }
        }
        jars.sort(Comparator.comparing(p -> p.toString().length()).reversed());

        Map<String, ProjectGraph> out = new LinkedHashMap<>();
        out.put(GraphSegmentIds.MAIN, new ProjectGraph());
        for (Path jar : jars) {
            out.put(GraphSegmentIds.segmentIdForJar(jar), new ProjectGraph());
        }

        for (ProjectGraph.GraphNode n : full.nodes.values()) {
            String seg = classify(n.filePath(), rootNorm, jars);
            out.get(seg).addNode(n);
        }
        for (List<ProjectGraph.GraphEdge> list : full.edgesFrom.values()) {
            for (ProjectGraph.GraphEdge e : list) {
                String seg = classify(e.filePath(), rootNorm, jars);
                out.get(seg).addEdge(e);
            }
        }
        return out;
    }

    static String classify(String filePath, String projectRootNorm, List<Path> jarsSortedLongestFirst) {
        if (filePath == null || filePath.isBlank() || "unknown".equals(filePath)) {
            return GraphSegmentIds.MAIN;
        }
        String n = filePath.replace('\\', '/');

        for (Path jar : jarsSortedLongestFirst) {
            if (belongsToJar(n, jar)) {
                return GraphSegmentIds.segmentIdForJar(jar);
            }
        }

        if (n.startsWith("jar:file:")) return GraphSegmentIds.MAIN;
        if (n.contains(".jar") && (n.contains("!") || n.startsWith("jar:"))) return GraphSegmentIds.MAIN;
        if (n.startsWith(projectRootNorm)) return GraphSegmentIds.MAIN;
        if (!n.startsWith("/") && !looksLikeWindowsAbsolute(n)) return GraphSegmentIds.MAIN;
        return GraphSegmentIds.MAIN;
    }

    private static boolean looksLikeWindowsAbsolute(String n) {
        return n.length() >= 2 && Character.isLetter(n.charAt(0)) && n.charAt(1) == ':';
    }

    static boolean belongsToJar(String filePathNorm, Path jarPath) {
        String jp = jarPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (filePathNorm.equals(jp)) return true;
        if (filePathNorm.startsWith(jp + "!") || filePathNorm.startsWith(jp + "/")) return true;
        if (filePathNorm.startsWith("jar:file:")) {
            try {
                String rest = filePathNorm.substring("jar:file:".length());
                int bang = rest.indexOf("!/");
                if (bang > 0) {
                    String jarUriPart = rest.substring(0, bang);
                    URI u = URI.create("file:" + jarUriPart);
                    Path insideJar = Path.of(u).toAbsolutePath().normalize();
                    return insideJar.equals(jarPath.toAbsolutePath().normalize());
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
