package com.example.mrrag.graph;

import com.example.mrrag.app.source.ProjectSource;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;

/**
 * Partitions a list of {@link ProjectSource} files into batches suitable for
 * parallel {@link spoon.Launcher} invocations.
 *
 * <h2>Algorithm — test-centric strategy</h2>
 * <ol>
 *   <li><b>Pre-scan</b> — each file is read line-by-line in parallel.
 *       Only the import block is scanned.</li>
 *   <li><b>Split by source root</b> — sources are separated into
 *       <em>test</em> ({@code src/test/java}) and <em>main</em> pools.
 *       If no test files are present the algorithm falls back to the
 *       flat package-only strategy.</li>
 *   <li><b>Group by package</b> — both test and main files are grouped
 *       purely by their {@code package} declaration.  No Union-Find
 *       merging is applied to either pool — merging via imports collapses
 *       everything into one giant component.</li>
 *   <li><b>Batch assembly</b> — each test-package group becomes the seed
 *       of one batch.  The main-package groups directly imported by any
 *       file in that test group are appended (each main-package group is
 *       added at most once per batch).  Main packages not referenced by
 *       any test go into a trailing catch-all batch.</li>
 *   <li><b>Bin packing</b> — if the number of assembled batches exceeds
 *       {@code numBatches}, batches are merged via the LPT heuristic.</li>
 * </ol>
 */
@Slf4j
@UtilityClass
public class SourceBatchPartitioner {

    private static final String TEST_ROOT = "src/test/java";

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static List<List<ProjectSource>> partition(List<ProjectSource> sources, int numBatches) {
        if (sources.isEmpty()) return List.of();
        if (numBatches <= 1)   return List.of(List.copyOf(sources));

        // Step 1: scan all files in parallel
        Map<String, SourceMeta> metaMap = scanAll(sources);

        // Step 2: split into test / main pools
        List<ProjectSource> testSources = new ArrayList<>();
        List<ProjectSource> mainSources = new ArrayList<>();
        for (ProjectSource src : sources) {
            if (src.path().contains(TEST_ROOT)) testSources.add(src);
            else                                mainSources.add(src);
        }

        if (testSources.isEmpty()) {
            log.info("SourceBatchPartitioner: no test sources found, using flat strategy");
            return flatPartition(sources, metaMap, numBatches);
        }

        // Step 3: group both pools by package only (no Union-Find)
        Map<String, List<ProjectSource>> testByPkg = groupByPkg(testSources, metaMap);
        Map<String, List<ProjectSource>> mainByPkg = groupByPkg(mainSources, metaMap);

        // Step 4: assemble batches — test-pkg + directly imported main-pkgs
        Set<String> referencedMainPkgs = new HashSet<>();
        List<List<ProjectSource>> batches = new ArrayList<>();

        for (List<ProjectSource> testGroup : testByPkg.values()) {
            Set<String> neededMainPkgs = new LinkedHashSet<>();
            for (ProjectSource testFile : testGroup) {
                for (String importedPkg : metaMap.get(testFile.path()).importedPackages) {
                    if (mainByPkg.containsKey(importedPkg)) {
                        neededMainPkgs.add(importedPkg);
                    }
                }
            }
            List<ProjectSource> batch = new ArrayList<>(testGroup);
            for (String pkg : neededMainPkgs) {
                batch.addAll(mainByPkg.get(pkg));
                referencedMainPkgs.add(pkg);
            }
            batches.add(batch);
        }

        // Step 5: catch-all for unreferenced main packages
        List<ProjectSource> catchAll = new ArrayList<>();
        for (Map.Entry<String, List<ProjectSource>> e : mainByPkg.entrySet()) {
            if (!referencedMainPkgs.contains(e.getKey())) catchAll.addAll(e.getValue());
        }
        if (!catchAll.isEmpty()) batches.add(catchAll);

        // Step 6: bin-pack down to numBatches
        List<List<ProjectSource>> result = binPack(batches, numBatches);

        log.info("SourceBatchPartitioner: {} test + {} main files, "
                + "{} test-pkgs, {} main-pkgs -> {} batches (total files in batches: {})",
                testSources.size(), mainSources.size(),
                testByPkg.size(), mainByPkg.size(), result.size(),
                result.stream().mapToInt(List::size).sum());
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------
    // Group by package (no merging)
    // ------------------------------------------------------------------

    private static Map<String, List<ProjectSource>> groupByPkg(
            List<ProjectSource> pool, Map<String, SourceMeta> metaMap) {
        Map<String, List<ProjectSource>> byPkg = new LinkedHashMap<>();
        for (ProjectSource src : pool) {
            String pkg = metaMap.get(src.path()).pkg;
            byPkg.computeIfAbsent(pkg, k -> new ArrayList<>()).add(src);
        }
        return byPkg;
    }

    // ------------------------------------------------------------------
    // Fallback: flat strategy (no test sources)
    // ------------------------------------------------------------------

    private static List<List<ProjectSource>> flatPartition(
            List<ProjectSource> sources,
            Map<String, SourceMeta> metaMap,
            int numBatches) {
        Map<String, List<ProjectSource>> byPkg = groupByPkg(sources, metaMap);
        List<List<ProjectSource>> result = binPack(new ArrayList<>(byPkg.values()), numBatches);
        log.info("SourceBatchPartitioner (flat): {} files, {} pkgs -> {} batches",
                sources.size(), byPkg.size(), result.size());
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------
    // Step 1: parallel pre-scan
    // ------------------------------------------------------------------

    private static Map<String, SourceMeta> scanAll(List<ProjectSource> sources) {
        List<CompletableFuture<SourceMeta>> futures = sources.stream()
                .map(src -> CompletableFuture.supplyAsync(
                        () -> scanOne(src), ForkJoinPool.commonPool()))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, SourceMeta> result = new ConcurrentHashMap<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            result.put(sources.get(i).path(), futures.get(i).join());
        }
        return result;
    }

    private static SourceMeta scanOne(ProjectSource src) {
        String pkg = derivePkgFromPath(src.path());
        Set<String> importedPackages = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(src.content()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("//")
                        || trimmed.startsWith("/*")
                        || trimmed.startsWith("*")) continue;
                if (trimmed.startsWith("package ")) {
                    pkg = trimmed.substring(8).replace(";", "").strip();
                    continue;
                }
                if (trimmed.startsWith("import ")) {
                    String fqn = trimmed.substring(7)
                            .replace("static ", "").replace(";", "").strip();
                    int dot = fqn.lastIndexOf('.');
                    if (dot > 0) {
                        String importedPkg = fqn.substring(0, dot);
                        if (!isExternal(importedPkg)) importedPackages.add(importedPkg);
                    }
                    continue;
                }
                break;
            }
        } catch (Exception e) {
            log.warn("SourceBatchPartitioner: failed to scan {}: {}", src.path(), e.getMessage());
        }
        return new SourceMeta(pkg, importedPackages);
    }

    // ------------------------------------------------------------------
    // Greedy bin-packing (LPT)
    // ------------------------------------------------------------------

    private static List<List<ProjectSource>> binPack(
            List<List<ProjectSource>> slices, int numBatches) {
        List<List<ProjectSource>> sorted = new ArrayList<>(slices);
        sorted.sort((a, b) -> Integer.compare(b.size(), a.size()));

        int n = Math.min(numBatches, sorted.size());
        PriorityQueue<Bucket> heap = new PriorityQueue<>(Comparator.comparingInt(b -> b.size));
        for (int i = 0; i < n; i++) heap.add(new Bucket());

        for (List<ProjectSource> slice : sorted) {
            Bucket lightest = heap.poll();
            assert lightest != null;
            lightest.files.addAll(slice);
            lightest.size += slice.size();
            heap.add(lightest);
        }

        return heap.stream()
                .filter(b -> !b.files.isEmpty())
                .map(b -> Collections.unmodifiableList(b.files))
                .toList();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String derivePkgFromPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash).replace('/', '.') : "<default>";
    }

    private static boolean isExternal(String pkg) {
        return pkg.startsWith("java.")          ||
               pkg.startsWith("javax.")         ||
               pkg.startsWith("jakarta.")       ||
               pkg.startsWith("org.")           ||
               pkg.startsWith("io.")            ||
               pkg.startsWith("com.google.")    ||
               pkg.startsWith("com.fasterxml.") ||
               pkg.startsWith("lombok")         ||
               pkg.startsWith("reactor.")       ||
               pkg.startsWith("kotlin.")        ||
               pkg.startsWith("scala.");
    }

    // ------------------------------------------------------------------
    // Internal data classes
    // ------------------------------------------------------------------

    private record SourceMeta(String pkg, Set<String> importedPackages) {}

    private static final class Bucket {
        final List<ProjectSource> files = new ArrayList<>();
        int size = 0;
    }
}
