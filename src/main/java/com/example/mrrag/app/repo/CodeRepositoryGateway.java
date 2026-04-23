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
     *
     * @param namespace наименование владельца/группы (не может быть пустым или null)
     * @param repo      наименование репозитория проекта (не может быть пустым или null)
     * @param branch    имя ветки (не может быть пустым или null); если в запросе не указана — подставляется ветка по умолчанию (например, main)
     * @param commit    коммит из ветки, который нужно клонировать; может быть null, тогда используется ветка по умолчанию
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return путь к локальной директории репозитория
     * @throws CodeRepositoryException если клонирование не удалось
     */
    Path cloneProject(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String commit, boolean force, @Nullable String token);

    /**
     * Получает Merge Request по namespace/repo и mrIid через GitLab API.
     *
     * @param namespace наименование владельца/группы (не может быть пустым или null)
     * @param repo      наименование репозитория проекта (не может быть пустым или null)
     * @param mrIid     внутренний идентификатор MR (должен быть положительным числом)
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return объект MergeRequest
     * @throws CodeRepositoryException если проект или MR не найден, или токен недоступен
     */
    MergeRequest getMergeRequest(@NotBlank String namespace, @NotBlank String repo, long mrIid, @Nullable String token);

    /**
     * Получает diffs текущего Merge Request через GitLab API.
     *
     * @param namespace наименование владельца/группы (не может быть пустым или null)
     * @param repo      наименование репозитория проекта (не может быть пустым или null)
     * @param mrIid     внутренний идентификатор MR (должен быть положительным числом)
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return список diff‑записей для MR; никогда не возвращает null
     * @throws CodeRepositoryException если проект или MR не найден, или токен недоступен
     */
    List<Diff> getMrDiffs(@NotBlank String namespace, @NotBlank String repo, long mrIid, @Nullable String token);

    /**
     * Получает сырой контент файла по пути и ревизии.
     *
     * @param namespace наименование владельца/группы (не может быть пустым или null)
     * @param repo      наименование репозитория проекта (не может быть пустым или null)
     * @param branch    ветка (не может быть пустой или null)
     * @param filePath  относительный путь к файлу в репозитории (не может быть пустым или null)
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return содержимое файла как строка (UTF‑8)
     * @throws CodeRepositoryException если файл не найден, проект недоступен или токен недостаточен
     */
    String getFileContent(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @NotBlank String filePath, @Nullable String token);

    /**
     * Получает дерево репозитория (список файлов/директорий) по ревизии.
     *
     * @param namespace наименование владельца/группы (не может быть пустым или null)
     * @param repo      наименование репозитория проекта (не может быть пустым или null)
     * @param branch    ветка (не может быть пустой или null)
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return список TreeItem (имя, тип, путь и т.п.); никогда не возвращает null
     */
    List<TreeItem> getRepositoryTree(@NotBlank String namespace, @NotBlank String repo, @NotBlank String branch, @Nullable String token);

    /**
     * Удаляет локальный клон репозитория.
     *
     * @param repoDir путь к локальной директории репозитория (не может быть null)
     * @throws CodeRepositoryException если удаление не удалось (например, каталог не существует, доступ запрещён)
     */
    void cleanup(Path repoDir);
}
