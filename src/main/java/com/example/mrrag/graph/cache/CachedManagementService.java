package com.example.mrrag.graph.cache;

import com.example.mrrag.app.repo.CodeRepositoryGateway;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class CachedManagementService {

    private final GraphCache graphCache;
    private final GraphBuilder graphBuilder;
    private final GraphPatcher patcher;
    private final CodeRepositoryGateway gateway;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {

        //1. Получаем последний коммит ветки
        String lastCommit = gateway.getLastCommit(key.namespace(), key.repo(), key.branch(), token);

        // 1. Если графа в кэше нет, то клонируем по последнему коммиту (сайд эффект возможен по коммиту)
        VersionedGraph cachedGraph = graphCache.get(key).orElseGet(() -> {
            Path path = gateway.clone(key.namespace(), key.repo(), key.branch(), token);
            ProjectGraph projectGraph = graphBuilder.buildGraph(new LocalProjectSourceProvider(key, path));
            graphCache.put(key, new VersionedGraph(lastCommit, projectGraph));
            return graphCache.get(key).orElseThrow();
        });


        //2. Сравниваем последний коммит и коммит графа
        if (lastCommit.equals(cachedGraph.commitSha()))
            return cachedGraph.graph();

        //3. Если коммиты не совпадают, обновляем граф
        Path path = gateway.pull(key.namespace(), key.repo(), key.branch(), token);
        ProjectSourceProvider provider = new LocalProjectSourceProvider(key, path);

        log.info("CachedSourceManagementService: patching {} {} → {}", key, cachedGraph.commitSha(), lastCommit);

        //3.2 Получаем изменные файлы в новых коммитах и оставляем только их для графа
        List<String> changedFiles = gateway.getCommitDiff(key.namespace(), key.repo(), cachedGraph.commitSha(), lastCommit, token);
        List<ProjectSource> changedSources = provider.getSources().stream()
                .filter(src -> changedFiles.contains(src.path()))
                .toList();

        log.info("CachedSourceManagementService: {} changed .java files", changedFiles.size());

        //3.3 Удаляем протухшие файлы в графе и добавляем новые
        patcher.removeFiles(cachedGraph.graph(), changedFiles);
        patcher.addFiles(cachedGraph.graph(), changedSources,
                provider.localProjectRoot().orElseThrow());

        graphCache.put(key, new VersionedGraph(lastCommit, cachedGraph.graph()));
        return cachedGraph.graph();
    }

    /**
     * Evicts both the clone entry and the graph for the branch.
     * Next call to {@link #getOrBuildGraph} re-clones and rebuilds.
     */
    public void invalidate(ProjectKey key) {
        graphCache.invalidate(key);
        log.info("CachedSourceManagementService.invalidate: evicted {}", key);
    }
}
