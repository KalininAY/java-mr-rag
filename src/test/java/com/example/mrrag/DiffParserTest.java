package com.example.mrrag;

import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.DiffParser;
import org.gitlab4j.api.models.Diff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parseSimpleAddedLines() {
        Diff diff = new Diff();
        diff.setNewPath("src/main/java/Foo.java");
        diff.setDiff("""
                @@ -10,3 +10,5 @@
                  void existingMethod() {
                +     // added comment
                +     int x = 1;
                  }
                """);

        Set<ChangedLine> lines = parser.parse(List.of(diff));

        assertThat(lines).isNotEmpty();
        long addedCount = lines.stream().filter(l -> l.type() == ChangedLine.LineType.ADD).count();
        assertThat(addedCount).isEqualTo(2);
    }

    @Test
    void parseDeletedLines() {
        Diff diff = new Diff();
        diff.setNewPath("src/main/java/Bar.java");
        diff.setDiff("""
                @@ -5,4 +5,3 @@
                  public void foo() {
                -     int unused = 42;
                      return;
                  }
                """);

        Set<ChangedLine> lines = parser.parse(List.of(diff));
        long deletedCount = lines.stream().filter(l -> l.type() == ChangedLine.LineType.DELETE).count();
        assertThat(deletedCount).isEqualTo(1);
    }

    @Test
    void skipNonJavaFiles() {
        Diff diff = new Diff();
        diff.setNewPath("README.md");
        diff.setDiff("@@ -1,1 +1,2 @@\n+some text\n");

        Set<ChangedLine> lines = parser.parse(List.of(diff));
        assertThat(lines).isEmpty();
    }
}
