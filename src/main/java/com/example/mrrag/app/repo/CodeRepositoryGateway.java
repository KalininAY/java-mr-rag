package com.example.mrrag.app.repo;

import jakarta.validation.constraints.NotBlank;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.lang.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over Git clone + GitLab MR/diff API.
 * Does not depend on the {@code app} layer.
 */
public interface CodeRepositoryGateway {

    /**
     * Клонирует проект GitLab в указанный каталог.
     */
    Path cloneProject(@NotBlank String namespace, @NotBlank String repo,
                      @NotBlank String branch, @Nullable String commit,
                      boolean force, @Nullable String token);

    /**
     * Получает HEAD-коммит ветки через GitLab API (без клонирования).
     *
     * @return SHA последнего коммита ветки
     * @throws CodeRepositoryException если ветка не найдена или токен недоступен
     */
    String getBranchHeadSha(@NotBlank String namespace, @NotBlank String repo,
                            @NotBlank String branch, @Nullable String token);

    /**
     * Получает Merge Request по namespace/repo и mrIid через GitLab API.
     */
    MergeRequest getMergeRequest(@NotBlank String namespace, @NotBlank String repo,
                                 long mrIid, @Nullable String token);

    /**
     * Получает diffs текущего Merge Request через GitLab API.
     */
    List<Diff> getMrDiffs(@NotBlank String namespace, @NotBlank String repo,
                          long mrIid, @Nullable String token);

    /**
     * Получает сырой контент файла по пути и ревизии.
     */
    String getFileContent(@NotBlank String namespace, @NotBlank String repo,
                          @NotBlank String branch, @NotBlank String filePath,
                          @Nullable String token);

    /**
     * Получает дерево репозитория (список файлов/директорий) по ревизии.
     */
    List<TreeItem> getRepositoryTree(@NotBlank String namespace, @NotBlank String repo,
                                     @NotBlank String branch, @Nullable String token);

    /**
     * Удаляет локальный клон репозитория.
     */
    void cleanup(Path repoDir);
}
