package com.example.mrrag.app.repo;

import jakarta.validation.constraints.NotBlank;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.lang.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over Git clone + GitLab MR/diff API
 * does not depend on the {@code app} layer.
 */
public interface CodeRepositoryGateway {

    /**
     * Клонирует проект GitLab в указанный каталог.
     */
    Path cloneProject(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String commit, boolean force, @Nullable String token);

    /**
     * Получает Merge Request по namespace/repo и mrIid через GitLab API.
     */
    MergeRequest getMergeRequest(@NotBlank String namespace, @NotBlank String repo, long mrIid, @Nullable String token);

    /**
     * Получает diffs текущего Merge Request через GitLab API.
     */
    List<Diff> getMrDiffs(@NotBlank String namespace, @NotBlank String repo, long mrIid, @Nullable String token);

    /**
     * Получает сырой контент файла по пути и ревизии.
     */
    String getFileContent(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @NotBlank String filePath, @Nullable String token);

    /**
     * Получает дерево репозитория (список файлов/директорий) по ревизии.
     */
    List<TreeItem> getRepositoryTree(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String token);

    /**
     * Удаляет локальный клон репозитория.
     */
    void cleanup(Path repoDir);

    /**
     * Резолвит имя ветки или тег в полный 40-символьный commit SHA.
     *
     * <p>Used by {@link com.example.mrrag.graph.cache.IncrementalGraphBuilder}
     * to determine whether the cached graph is still fresh.
     *
     * @param namespace наименование владельца/группы
     * @param repo      наименование репозитория
     * @param ref       ветка, тег или уже полный SHA
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return полный 40-символьный commit SHA
     * @throws CodeRepositoryException если реф не найден
     */
    String resolveCommitSha(@NotBlank String namespace, @NotBlank String repo,
                            @NotBlank String ref, @Nullable String token);

    /**
     * Возвращает список репозиторно-относительных путей {@code .java}-файлов,
     * изменённых между двумя коммитами.
     *
     * <p>Used by {@link com.example.mrrag.graph.cache.IncrementalGraphBuilder}
     * to decide which files need to be re-parsed during a patch update.
     *
     * @param namespace наименование владельца/группы
     * @param repo      наименование репозитория
     * @param fromSha   SHA коммита от (исключительно)
     * @param toSha     SHA коммита до (включительно)
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return непустой список репозиторных путей (e.g. {@code "src/main/java/com/example/Foo.java"})
     * @throws CodeRepositoryException если API недоступен
     */
    List<String> getCommitDiff(@NotBlank String namespace, @NotBlank String repo,
                               @NotBlank String fromSha, @NotBlank String toSha,
                               @Nullable String token);
}
