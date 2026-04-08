package com.example.mrrag.app.service;

import java.util.List;

public record TreeItem(
        String name,
        String path,
        String type,     // "blob", "tree"
        String mode,
        String id       // Git‑OID, если нужен
) {
    public static TreeItem from(org.gitlab4j.api.models.TreeItem item) {
        return new TreeItem(
                item.getName(),
                item.getPath(),
                item.getType() != null ? item.getType().name() : null,
                item.getMode(),
                item.getId()
        );
    }

    public static List<TreeItem> listFrom(List<org.gitlab4j.api.models.TreeItem> items) {
        return items.stream()
                .map(TreeItem::from)
                .toList();
    }
}