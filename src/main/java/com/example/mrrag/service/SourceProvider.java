package com.example.mrrag.service;

import java.nio.file.Path;
import java.util.List;

public interface SourceProvider {

    List<String> sourceProvide(String repoUrl, String branch, String gitToken, Boolean force);

    List<String> sourceProvider(Path rootProject);
}
