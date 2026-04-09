package com.example.mrrag.app.repo;

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
     * @param path       путь к локальной директории репозитория
     * @param projectUrl ссылка на проект
     * @param branch     ветка, которую нужно клонировать
     * @param commit     ветка, которую нужно клонировать
     * @param token      персональный токен; если null — используется токен по умолчанию
     * @return путь к локальной директории репози тория
     * @throws CodeRepositoryException если клонирование не удалось
     */
    Path cloneProject(Path path, String projectUrl, String branch, @Nullable String commit, @Nullable String token);

    /**
     * Обновляет локальный репозиторий (pull/fetch).
     *
     * @param repoDir путь к локальному репозиторию
     * @param branch  ветка, которую нужно обновить
     * @param token   персональный токен; если null — используется токен по умолчанию
     * @throws CodeRepositoryException если обновление не удалось
     */
    void updateRepository(Path repoDir, String branch, @Nullable String token);

    /**
     * Выполняет checkout указанной ветки или коммита в локальном репозитории.
     *
     * @param repoDir        путь к локальному репозиторию
     * @param branchOrCommit ветка или коммит, на который нужно перейти
     * @param token          персональный токен; если null — используется токен по умолчанию
     * @throws CodeRepositoryException если checkout не удался
     */
    void checkout(Path repoDir, String branchOrCommit, @Nullable String token);


    /**
     * Получает Merge Request по projectId и mrIid через GitLab API.
     *
     * @param projectId идентификатор проекта в GitLab
     * @param mrIid     внутренний идентификатор MR
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return объект MergeRequest
     * @throws CodeRepositoryException если получение MR не удалось
     */
    MergeRequest getMergeRequest(long projectId, long mrIid, @Nullable String token);

    /**
     * Получает diffs текущего Merge Request через GitLab API.
     *
     * @param projectId идентификатор проекта в GitLab
     * @param mrIid     внутренний идентификатор MR
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return список diff‑записей для MR
     * @throws CodeRepositoryException если получение diffs не удалось
     */
    List<Diff> getMrDiffs(long projectId, long mrIid, @Nullable String token);


    /**
     * Получает сырой контент файла по пути и ревизии.
     *
     * @param projectId идентификатор проекта GitLab
     * @param revision  ветка/тег/коммит (например, "main", "develop", коммит‑хэш)
     * @param filePath  относительный путь к файлу в репозитории
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return содержимое файла как строка (UTF‑8)
     * @throws CodeRepositoryException если получение содержимого файла не удалось
     */
    String getFileContent(long projectId, String revision, String filePath, @Nullable String token);


    /**
     * Получает дерево репозитория (список файлов/директорий) по ревизии.
     *
     * @param projectId идентификатор проекта GitLab
     * @param revision  ветка/тег/коммит
     * @param token     персональный токен; если null — используется токен по умолчанию
     * @return список TreeItem (имя, тип, путь и т.п.)
     */
    List<TreeItem> getRepositoryTree(long projectId, String revision, @Nullable String token);

    /**
     * Удаляет локальный клон репозитория.
     *
     * @param repoDir путь к локальной директории репозитория
     * @throws CodeRepositoryException если удаление не удалось
     */
    void cleanup(Path repoDir);
}
