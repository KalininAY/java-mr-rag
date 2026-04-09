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
 *   <li><b>Pre-scan</b> — each file is read line-by-line in parallel via
 *       {@link ForkJoinPool#commonPool()}.  Only the import block is processed.</li>
 *   <li><b>Package grouping</b> — files that share the same package are kept
 *       together; they can reference each other without imports.</li>
 *   <li><b>Union-Find</b> — cross-package {@code import} statements that
 *       reference other project packages merge those packages into a single
 *       component.  External packages are ignored.</li>
 *   <li><b>Oversized-component splitting</b> — if a component is larger than
 *       {@code ceil(total/N) * OVERSIZE_FACTOR} it is sub-partitioned by
 *       package into slices of the target size.  Files whose imports cross a
 *       slice boundary are duplicated into both neighbouring slices so Spoon
 *       can still resolve the cross-slice reference.  This bounds the maximum
 *       batch size while tolerating a small amount of duplication.</li>
 *   <li><b>Bin packing</b> — resulting slices are sorted descending by size
 *       and assigned to the {@code numBatches} least-loaded bucket (LPT
 *       heuristic, within 4/3 of optimal).</li>
 * </ol>
 */
@Slf4j
@UtilityClass
public class SourceBatchPartitioner {

    /**
     * A component whose size exceeds {@code ceil(total/N) * OVERSIZE_FACTOR}
     * is eligible for sub-partitioning.  Value 1.5 allows a 50 %% overage
     * before we start splitting.
     */
    private static final double OVERSIZE_FACTOR = 1.5;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

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

        // Step 4: split oversized components into smaller slices
        int targetSize = (int) Math.ceil((double) sources.size() / numBatches);
        List<List<ProjectSource>> slices = splitComponents(byComponent, metaMap, targetSize);

        // Step 5: greedy bin-packing into numBatches buckets
        List<List<ProjectSource>> result = binPack(slices, numBatches);

        log.info("SourceBatchPartitioner: {} files, {} packages, {} components, {} slices -> {} batches",
                sources.size(), knownPackages.size(), byComponent.size(), slices.size(), result.size());
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
            SourceMeta m = futures.get(i).join();
            result.put(sources.get(i).path(), m);
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
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;
                if (trimmed.startsWith("package ")) {
                    pkg = trimmed.substring(8).replace(";", "").strip();
                    continue;
                }
                if (trimmed.startsWith("import ")) {
                    String fqn = trimmed.substring(7).replace("static ", "").replace(";", "").strip();
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
    // Step 4: oversized-component splitting
    // ------------------------------------------------------------------

    /**
     * Iterates over all components.  Components whose size does not exceed
     * {@code targetSize * OVERSIZE_FACTOR} are emitted as-is.  Oversized
     * components are split by package into slices of approximately
     * {@code targetSize} files.
     *
     * <p>Border files (files whose imported packages belong to a different
     * slice) are duplicated into both the owning slice and every neighbouring
     * slice they reference.  This allows Spoon to resolve cross-slice
     * references at the cost of parsing a small number of files twice.
     */
    private static List<List<ProjectSource>> splitComponents(
            Map<String, List<ProjectSource>> byComponent,
            Map<String, SourceMeta> metaMap,
            int targetSize) {

        int threshold = (int) Math.ceil(targetSize * OVERSIZE_FACTOR);
        List<List<ProjectSource>> result = new ArrayList<>();

        for (List<ProjectSource> component : byComponent.values()) {
            if (component.size() <= threshold) {
                result.add(component);
                continue;
            }

            // Group files of this component by package
            Map<String, List<ProjectSource>> byPkg = new LinkedHashMap<>();
            for (ProjectSource src : component) {
                String pkg = metaMap.get(src.path()).pkg;
                byPkg.computeIfAbsent(pkg, k -> new ArrayList<>()).add(src);
            }

            // Assign packages to slices sequentially until each slice is full
            List<List<ProjectSource>> componentSlices = new ArrayList<>();
            List<ProjectSource> current = new ArrayList<>();
            for (List<ProjectSource> pkgFiles : byPkg.values()) {
                if (!current.isEmpty() && current.size() + pkgFiles.size() > threshold) {
                    componentSlices.add(current);
                    current = new ArrayList<>();
                }
                current.addAll(pkgFiles);
            }
            if (!current.isEmpty()) componentSlices.add(current);

            if (componentSlices.size() <= 1) {
                // Could not split meaningfully — emit as one slice
                result.add(component);
                continue;
            }

            // Build a reverse index: path -> slice index
            Map<String, Integer> pathToSlice = new HashMap<>();
            for (int si = 0; si < componentSlices.size(); si++) {
                for (ProjectSource src : componentSlices.get(si)) {
                    pathToSlice.put(src.path(), si);
                }
            }

            // Build a package -> slice index map for quick cross-slice lookup
            Map<String, Integer> pkgToSlice = new HashMap<>();
            for (int si = 0; si < componentSlices.size(); si++) {
                for (ProjectSource src : componentSlices.get(si)) {
                    pkgToSlice.put(metaMap.get(src.path()).pkg, si);
                }
            }

            // Build a path -> ProjectSource lookup for duplication
            Map<String, ProjectSource> pathToSrc = new HashMap<>();
            for (ProjectSource src : component) pathToSrc.put(src.path(), src);

            // Duplicate border files into neighbouring slices
            // A file is a border file if it imports a package assigned to a
            // different slice.  We add it (read-only duplicate) to that slice.
            List<Set<String>> extras = new ArrayList<>();
            for (int si = 0; si < componentSlices.size(); si++) extras.add(new LinkedHashSet<>());

            for (ProjectSource src : component) {
                int ownerSlice = pathToSlice.get(src.path());
                SourceMeta meta = metaMap.get(src.path());
                for (String importedPkg : meta.importedPackages) {
                    Integer targetSlice = pkgToSlice.get(importedPkg);
                    if (targetSlice != null && targetSlice != ownerSlice) {
                        // Duplicate this file into the slice that owns the imported package
                        extras.get(targetSlice).add(src.path());
                    }
                }
            }

            for (int si = 0; si < componentSlices.size(); si++) {
                List<ProjectSource> slice = new ArrayList<>(componentSlices.get(si));
                for (String extraPath : extras.get(si)) {
                    slice.add(pathToSrc.get(extraPath));
                }
                result.add(slice);
            }

            log.debug("SourceBatchPartitioner: oversized component ({} files) split into {} slices",
                    component.size(), componentSlices.size());
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Step 5: greedy bin-packing
    // ------------------------------------------------------------------

    private static List<List<ProjectSource>> binPack(List<List<ProjectSource>> slices, int numBatches) {
        List<List<ProjectSource>> sorted = new ArrayList<>(slices);
        sorted.sort((l1, l2) -> Integer.compare(l2.size(), l1.size()));

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
        return pkg.startsWith("java.")        ||
               pkg.startsWith("javax.")       ||
               pkg.startsWith("jakarta.")     ||
               pkg.startsWith("org.")         ||
               pkg.startsWith("io.")          ||
               pkg.startsWith("com.google.")  ||
               pkg.startsWith("com.fasterxml.") ||
               pkg.startsWith("lombok")       ||
               pkg.startsWith("reactor.")     ||
               pkg.startsWith("kotlin.")      ||
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
