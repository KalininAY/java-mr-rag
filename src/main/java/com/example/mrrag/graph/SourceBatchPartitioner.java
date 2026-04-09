package com.example.mrrag.graph;

import com.example.mrrag.app.source.ProjectSource;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Partitions a list of {@link ProjectSource} files into batches suitable for
 * parallel {@link spoon.Launcher} invocations.
 *
 * <h2>Algorithm — test-centric strategy</h2>
 * <ol>
 *   <li><b>Pre-scan</b> — each file is read line-by-line in parallel.
 *       Only the import block is scanned.</li>
 *   <li><b>Split by source root</b> — sources are separated into
 *       <em>test</em> ({@code src/test/java}) and <em>main</em>
 *       ({@code src/main/java}) pools.  If no test files are present the
 *       algorithm falls back to the flat package+Union-Find+bin-packing
 *       strategy.</li>
 *   <li><b>Test groups — by package only</b> — test files are grouped
 *       purely by their {@code package} declaration, without any
 *       Union-Find merging.  Tests in different packages are treated as
 *       independent; merging them via imports produces one giant component.</li>
 *   <li><b>Main groups — Union-Find</b> — main files are grouped using
 *       Union-Find over intra-main imports so that Spoon can resolve
 *       intra-main type references correctly.</li>
 *   <li><b>Batch assembly</b> — each test-group becomes the seed of one
 *       batch.  The <em>direct</em> main-group dependencies of that
 *       test-group (main packages directly imported by any file in the
 *       test-group) are appended to the batch.  Main files not referenced
 *       by any test-group go into a trailing catch-all batch.</li>
 *   <li><b>Bin packing</b> — if the number of assembled batches exceeds
 *       {@code numBatches}, the smallest batches are merged via LPT.</li>
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

        // Step 3a: group test files by package only (no Union-Find)
        Map<String, List<ProjectSource>> testByPkg = new LinkedHashMap<>();
        for (ProjectSource src : testSources) {
            String pkg = metaMap.get(src.path()).pkg;
            testByPkg.computeIfAbsent(pkg, k -> new ArrayList<>()).add(src);
        }
        List<List<ProjectSource>> testGroups = new ArrayList<>(testByPkg.values());

        // Step 3b: group main files via Union-Find on intra-main imports
        List<List<ProjectSource>> mainGroups = buildComponents(mainSources, metaMap);

        // Step 4: package -> mainGroup index
        Map<String, Integer> pkgToMainGroup = new HashMap<>();
        for (int gi = 0; gi < mainGroups.size(); gi++) {
            for (ProjectSource src : mainGroups.get(gi)) {
                pkgToMainGroup.put(metaMap.get(src.path()).pkg, gi);
            }
        }

        // Step 5: assemble batches — test group + directly imported main groups
        Set<Integer> referencedMainGroups = new HashSet<>();
        List<List<ProjectSource>> batches = new ArrayList<>();

        for (List<ProjectSource> testGroup : testGroups) {
            Set<Integer> neededMainGroupIds = new LinkedHashSet<>();
            for (ProjectSource testFile : testGroup) {
                for (String importedPkg : metaMap.get(testFile.path()).importedPackages) {
                    Integer mgId = pkgToMainGroup.get(importedPkg);
                    if (mgId != null) neededMainGroupIds.add(mgId);
                }
            }
            List<ProjectSource> batch = new ArrayList<>(testGroup);
            for (int mgId : neededMainGroupIds) {
                batch.addAll(mainGroups.get(mgId));
                referencedMainGroups.add(mgId);
            }
            batches.add(batch);
        }

        // Step 6: catch-all for unreferenced main files
        List<ProjectSource> catchAll = new ArrayList<>();
        for (int gi = 0; gi < mainGroups.size(); gi++) {
            if (!referencedMainGroups.contains(gi)) catchAll.addAll(mainGroups.get(gi));
        }
        if (!catchAll.isEmpty()) batches.add(catchAll);

        // Step 7: bin-pack down to numBatches if needed
        List<List<ProjectSource>> result = binPack(batches, numBatches);

        log.info("SourceBatchPartitioner: {} test + {} main files, "
                + "{} test-pkgs, {} main-groups -> {} batches",
                testSources.size(), mainSources.size(),
                testGroups.size(), mainGroups.size(), result.size());
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------
    // Union-Find component builder (main pool only)
    // ------------------------------------------------------------------

    private static List<List<ProjectSource>> buildComponents(
            List<ProjectSource> pool, Map<String, SourceMeta> metaMap) {

        if (pool.isEmpty()) return List.of();

        Set<String> poolPackages = pool.stream()
                .map(s -> metaMap.get(s.path()).pkg)
                .collect(Collectors.toSet());

        UnionFind<String> uf = new UnionFind<>();
        poolPackages.forEach(uf::add);

        for (ProjectSource src : pool) {
            SourceMeta meta = metaMap.get(src.path());
            for (String importedPkg : meta.importedPackages) {
                if (poolPackages.contains(importedPkg)) {
                    uf.union(meta.pkg, importedPkg);
                }
            }
        }

        Map<String, List<ProjectSource>> byRoot = new LinkedHashMap<>();
        for (ProjectSource src : pool) {
            String root = uf.find(metaMap.get(src.path()).pkg);
            byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(src);
        }
        return new ArrayList<>(byRoot.values());
    }

    // ------------------------------------------------------------------
    // Fallback: flat strategy (no test sources)
    // ------------------------------------------------------------------

    private static List<List<ProjectSource>> flatPartition(
            List<ProjectSource> sources,
            Map<String, SourceMeta> metaMap,
            int numBatches) {

        List<List<ProjectSource>> components = buildComponents(sources, metaMap);
        List<List<ProjectSource>> result = binPack(components, numBatches);
        log.info("SourceBatchPartitioner (flat): {} files, {} components -> {} batches",
                sources.size(), components.size(), result.size());
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
