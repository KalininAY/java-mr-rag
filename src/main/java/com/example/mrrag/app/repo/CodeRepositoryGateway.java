package com.example.mrrag.app.repo;

import jakarta.validation.constraints.NotBlank;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.lang.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over Git clone/pull operations and GitLab REST API.
 */
public interface CodeRepositoryGateway {

    /**
     * Клонирует проект GitLab в каталог с именем по коммиту/ветке.
     * Используется для разовых операций (e.g. remote endpoint).
     */
    Path clone(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String token);

    /**
     * Клонирует ветку в фиксированный каталог {@code cache/{namespace}_{repo}__{branch}}
     * при первом вызове; при повторном выполняет {@code git pull}.
     *
     * <p>Предназначен для кэширующего слоя: один живой клон на ветку,
     * обновляемый инкрементально, без SHA/timestamp в пути.
     *
     * @return путь к локальному клону
     */
    Path pull(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String token);

    /**
     * Резолвит имя ветки или тег в полный 40-символьный commit SHA.
     */
    String getLastCommit(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String token);

    /**
     * Получает Merge Request по namespace/repo и mrIid.
     */
    MergeRequest getMergeRequest(@NotBlank String namespace, @NotBlank String repo,
                                 long mrIid, @Nullable String token);

    /**
     * Получает diffs Merge Request.
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
     * Получает дерево репозитория по ревизии.
     */
    List<TreeItem> getRepositoryTree(@NotBlank String namespace, @NotBlank String repo,
                                     @NotBlank String branch, @Nullable String token);

    /**
     * Удаляет локальный клон репозитория.
     */
    void cleanup(Path repoDir);



    /**
     * Возвращает список репозиторно-относительных путей {@code .java}-файлов,
     * изменённых между двумя коммитами (fromSha exclusive, toSha inclusive).
     */
    List<String> getCommitDiff(@NotBlank String namespace, @NotBlank String repo,
                               @NotBlank String fromSha, @NotBlank String toSha,
                               @Nullable String token);

    String getPath(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch);
}
