package com.example.mrrag.review.spi;

import com.example.mrrag.app.service.CodeRepositoryException;
import com.example.mrrag.app.service.TreeItem;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;

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
     * @param projectId идентификатор проекта в GitLab
     * @param branch    ветка, которую нужно клонировать
     * @param path      путь к локальной директории репози тория
     * @return путь к локальной директории репози тория
     * @throws CodeRepositoryException если клонирование не удалось
     */
    Path cloneProject(long projectId, String branch, Path path);

    /**
     * Обновляет локальный репозиторий (pull/fetch).
     *
     * @param repoDir путь к локальному репозиторию
     * @param branch  ветка, которую нужно обновить
     * @throws CodeRepositoryException если обновление не удалось
     */
    void updateRepository(Path repoDir, String branch);

    /**
     * Выполняет checkout указанной ветки или коммита в локальном репозитории.
     *
     * @param repoDir        путь к локальному репозиторию
     * @param branchOrCommit ветка или коммит, на который нужно перейти
     * @throws CodeRepositoryException если checkout не удался
     */
    void checkout(Path repoDir, String branchOrCommit);


    /**
     * Получает Merge Request по projectId и mrIid через GitLab API.
     *
     * @param projectId идентификатор проекта в GitLab
     * @param mrIid     внутренний идентификатор MR
     * @return объект MergeRequest
     * @throws CodeRepositoryException если получение MR не удалось
     */
    MergeRequest getMergeRequest(long projectId, long mrIid);

    /**
     * Получает diffs текущего Merge Request через GitLab API.
     *
     * @param projectId идентификатор проекта в GitLab
     * @param mrIid     внутренний идентификатор MR
     * @return список diff‑записей для MR
     * @throws CodeRepositoryException если получение diffs не удалось
     */
    List<Diff> getMrDiffs(long projectId, long mrIid);

    /**
     * Получает сырой контент файла по пути и ревизии.
     *
     * @param projectId идентификатор проекта GitLab
     * @param ref       ветка/тег/коммит (например, "main", "develop", коммит‑хэш)
     * @param filePath  относительный путь к файлу в репозитории (например, "src/main/java/Foo.java")
     * @throws CodeRepositoryException если получение содержимого файла не удалось
     * @return содержимое файла как строка (UTF‑8)
     */
    String getFileContent(long projectId, String ref, String filePath);

    /**
     * Получает дерево репозитория (список файлов/директорий) по ревизии.
     *
     * @param projectId идентификатор проекта GitLab
     * @param ref       ветка/тег/коммит
     * @return список TreeItem (имя, тип, путь и т.п.)
     */
    List<TreeItem> getRepositoryTree(long projectId, String ref);


    /**
     * Удаляет локальный клон репозитория.
     *
     * @param repoDir путь к локальной директории репозитория
     * @throws CodeRepositoryException если удаление не удалось
     */
    void cleanup(Path repoDir);
}
