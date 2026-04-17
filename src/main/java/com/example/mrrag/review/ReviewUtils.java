package com.example.mrrag.review;

import com.example.mrrag.review.model.ChangedLine;

import java.util.Collection;
import java.util.stream.Collectors;

public abstract class ReviewUtils {

    public static String toString(Collection<ChangedLine> lines) {
        return lines.stream().map(ChangedLine::content).collect(Collectors.joining("\n"));
    }
}
