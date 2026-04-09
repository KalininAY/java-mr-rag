package com.example.mrrag.context.plan;

/**
 * Inclusive line range [from, to] within a single file.
 *
 * @param from first line (1-based, inclusive)
 * @param to   last line  (1-based, inclusive)
 */
public record LineRange(int from, int to) {

    public LineRange {
        if (from < 1)       throw new IllegalArgumentException("from must be >= 1, got " + from);
        if (to < from)      throw new IllegalArgumentException("to must be >= from, got to=" + to + " from=" + from);
    }

    /** Returns true when the two ranges overlap or are adjacent. */
    public boolean overlapsOrAdjacent(LineRange other) {
        return this.from <= other.to + 1 && other.from <= this.to + 1;
    }

    /** Merge two overlapping/adjacent ranges into the smallest enclosing range. */
    public LineRange mergeWith(LineRange other) {
        return new LineRange(Math.min(this.from, other.from), Math.max(this.to, other.to));
    }
}
