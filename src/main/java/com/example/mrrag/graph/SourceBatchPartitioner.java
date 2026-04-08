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
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Pre-scan</b> — each file is read line-by-line (streaming, no full load)
 *       in parallel via {@link ForkJoinPool#commonPool()}.  Only lines up to the
 *       first non-import declaration are processed, so the cost is proportional
 *       to the import block size — typically &lt; 50 lines.</li>
 *   <li><b>Package grouping</b> — files that share the same package directory are
 *       always placed together; they can reference each other without imports.</li>
 *   <li><b>Union-Find</b> — cross-package {@code import} statements that reference
 *       other project packages merge those packages into a single component.
 *       External packages ({@code java.*}, {@code org.*}, {@code io.*}, etc.) are
 *       ignored.  Path compression + union-by-rank give O(α(N)) per operation.</li>
 *   <li><b>Bin packing</b> — connected components are sorted descending by file
 *       count and greedily assigned to the {@code numBatches} least-loaded
 *       bucket, minimising load imbalance.</li>
 * </ol>
 *
 * <p>This guarantees that Spoon can fully resolve intra-component type
 * references, eliminating the qualified-name degradation that arises when
 * mutually-dependent files land in different batches.
 */
@Slf4j
@UtilityClass
public class SourceBatchPartitioner {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Partitions {@code sources} into at most {@code numBatches} batches.
     *
     * <p>If {@code sources} is empty, or {@code numBatches} is {@code <= 1},
     * a single batch containing all sources is returned immediately.
     *
     * @param sources    non-null list of project source files
     * @param numBatches desired number of output batches (usually {@code availableProcessors})
     * @return an unmodifiable list of non-empty batches
     */
    public static List<List<ProjectSource>> partition(List<ProjectSource> sources, int numBatches) {
        if (sources.isEmpty()) return List.of();
        if (numBatches <= 1)   return List.of(List.copyOf(sources));

        // Step 1: scan package declarations and imports in parallel
        Map<String, SourceMeta> metaMap = scanAll(sources);

        // Step 2: build Union-Find over packages
        Set<String> knownPackages = metaMap.values().stream()
                .map(m -> m.pkg)
                .collect(Collectors.toSet());

        UnionFind<String> uf = new UnionFind<>();
        knownPackages.forEach(uf::add);

        for (SourceMeta meta : metaMap.values()) {
            for (String importedPkg : meta.importedPackages) {
                if (knownPackages.contains(importedPkg)) {
                    uf.union(meta.pkg, importedPkg);
                }
            }
        }

        // Step 3: group files by component representative
        Map<String, List<ProjectSource>> byComponent = new LinkedHashMap<>();
        for (ProjectSource src : sources) {
            String pkg  = metaMap.get(src.path()).pkg;
            String root = uf.find(pkg);
            byComponent.computeIfAbsent(root, k -> new ArrayList<>()).add(src);
        }

        // Step 4: greedy bin-packing into numBatches buckets
        List<List<ProjectSource>> result = binPack(byComponent, numBatches);

        log.info("SourceBatchPartitioner: {} files, {} packages, {} components -> {} batches",
                sources.size(), knownPackages.size(), byComponent.size(), result.size());
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------
    // Step 1: parallel pre-scan
    // ------------------------------------------------------------------

    /**
     * Scans all source files in parallel, extracting only the package declaration
     * and import statements using a streaming {@link BufferedReader}.
     * The reader stops at the first line that is not a {@code package} or
     * {@code import} statement (or blank/comment), so the entire file body
     * is never loaded into memory beyond what the JVM read-ahead buffers.
     */
    private static Map<String, SourceMeta> scanAll(List<ProjectSource> sources) {
        List<CompletableFuture<SourceMeta>> futures = sources.stream()
                .map(src -> CompletableFuture.supplyAsync(
                        () -> scanOne(src), ForkJoinPool.commonPool()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, SourceMeta> result = new ConcurrentHashMap<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            SourceMeta m = futures.get(i).join();
            result.put(sources.get(i).path(), m);
        }
        return result;
    }

    /**
     * Streams the content of a single {@link ProjectSource} line-by-line using a
     * {@link BufferedReader} backed by a {@link StringReader}.  Parsing stops
     * as soon as a line is encountered that is neither blank, a line comment,
     * a {@code package} statement, nor an {@code import} statement — i.e. the
     * first real declaration in the file.
     *
     * <p>This avoids loading the full source content into a secondary data
     * structure; only the header is ever traversed.
     */
    private static SourceMeta scanOne(ProjectSource src) {
        String pkg = derivePkgFromPath(src.path());
        Set<String> importedPackages = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(src.content()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();

                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                    continue; // skip blanks and comments
                }

                if (trimmed.startsWith("package ")) {
                    // "package com.example.foo;" -> "com.example.foo"
                    pkg = trimmed.substring(8).replace(";", "").strip();
                    continue;
                }

                if (trimmed.startsWith("import ")) {
                    // "import static com.example.foo.Bar.method;" or "import com.example.foo.Bar;"
                    String fqn = trimmed.substring(7).replace("static ", "").replace(";", "").strip();
                    // drop last segment (class/member name) to get the package
                    int dot = fqn.lastIndexOf('.');
                    if (dot > 0) {
                        String importedPkg = fqn.substring(0, dot);
                        // skip wildcards-only packages and well-known external packages
                        if (!isExternal(importedPkg)) {
                            importedPackages.add(importedPkg);
                        }
                    }
                    continue;
                }

                // Any other non-blank, non-comment line signals end of header
                break;
            }
        } catch (Exception e) {
            log.warn("SourceBatchPartitioner: failed to scan {}: {}", src.path(), e.getMessage());
        }

        return new SourceMeta(pkg, importedPackages);
    }

    // ------------------------------------------------------------------
    // Step 4: greedy bin-packing
    // ------------------------------------------------------------------

    /**
     * Assigns components to buckets greedily: sort components by descending size,
     * then put each component into the currently least-loaded bucket.
     * This is the Longest Processing Time (LPT) approximation for makespan
     * minimisation — provably within 4/3 of optimal.
     */
    private static List<List<ProjectSource>> binPack(Map<String, List<ProjectSource>> byComponent, int numBatches) {

        // Sort components largest-first
        List<List<ProjectSource>> components = new ArrayList<>(byComponent.values());
        components.sort((l1,l2) -> Integer.compare(l2.size(), l1.size()));

        // Buckets backed by a min-heap on current load
        int n = Math.min(numBatches, components.size());
        PriorityQueue<Bucket> heap = new PriorityQueue<>(Comparator.comparingInt(b -> b.size));
        for (int i = 0; i < n; i++) heap.add(new Bucket());

        for (List<ProjectSource> component : components) {
            Bucket lightest = heap.poll();
            assert lightest != null;
            lightest.files.addAll(component);
            lightest.size += component.size();
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

    /**
     * Derives a fallback package name from the file path when no {@code package}
     * declaration is found (e.g. default package or scan failure).
     * Uses the parent directory path as a pseudo-package so that files in the
     * same directory still land in the same component.
     */
    private static String derivePkgFromPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash).replace('/', '.') : "<default>";
    }

    /**
     * Returns {@code true} for packages that are certainly external to the
     * project and should not drive Union-Find merges.
     */
    private static boolean isExternal(String pkg) {
        return pkg.startsWith("java.")   ||
               pkg.startsWith("javax.")  ||
               pkg.startsWith("jakarta.") ||
               pkg.startsWith("org.")    ||
               pkg.startsWith("io.")     ||
               pkg.startsWith("com.google.") ||
               pkg.startsWith("com.fasterxml.") ||
               pkg.startsWith("lombok")  ||
               pkg.startsWith("reactor.") ||
               pkg.startsWith("reactor") ||
               pkg.startsWith("kotlin.") ||
               pkg.startsWith("scala.");
    }

    // ------------------------------------------------------------------
    // Internal data classes
    // ------------------------------------------------------------------

    /** Holds the pre-scanned metadata for one source file. */
    private record SourceMeta(String pkg, Set<String> importedPackages) {}

    /** A mutable accumulator used during bin-packing. */
    private static final class Bucket {
        final List<ProjectSource> files = new ArrayList<>();
        int size = 0;
    }
}
