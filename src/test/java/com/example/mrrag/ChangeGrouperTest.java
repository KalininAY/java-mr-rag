package com.example.mrrag;

import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.ChangeGrouper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeGrouperTest {

    private final ChangeGrouper grouper = new ChangeGrouper();

    @Test
    void singleFileContiguousChangesOneGroup() {
        List<ChangedLine> lines = List.of(
                line("Foo.java", 10, ChangedLine.LineType.ADD),
                line("Foo.java", 11, ChangedLine.LineType.ADD),
                line("Foo.java", 12, ChangedLine.LineType.DELETE)
        );
        List<ChangeGroup> groups = grouper.group(lines);
        assertThat(groups).hasSize(1);
    }

    @Test
    void twoFilesProduceTwoGroups() {
        List<ChangedLine> lines = List.of(
                line("Foo.java", 10, ChangedLine.LineType.ADD),
                line("Bar.java", 20, ChangedLine.LineType.DELETE)
        );
        List<ChangeGroup> groups = grouper.group(lines);
        assertThat(groups).hasSize(2);
    }

    @Test
    void emptyLinesProduceNoGroups() {
        assertThat(grouper.group(List.of())).isEmpty();
    }

    private ChangedLine line(String file, int lineNo, ChangedLine.LineType type) {
        return new ChangedLine(file, lineNo, 0, "content", type);
    }
}
