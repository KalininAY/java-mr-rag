package com.example.mrrag.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Logback appender that stores log events in memory with monotonically
 * increasing IDs. Spring picks it up as a bean; logback.xml wires it in.
 *
 * <p>Capacity is capped at {@value #MAX_ENTRIES} entries — oldest are dropped
 * when the cap is reached.
 */
@Component
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    public static final int MAX_ENTRIES = 10_000;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final AtomicLong counter = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** Entries sorted by id ascending. */
    private final List<LogEntry> entries = new ArrayList<>(MAX_ENTRIES);

    @Override
    protected void append(ILoggingEvent event) {
        long id = counter.incrementAndGet();
        String ts = TS_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        LogEntry entry = new LogEntry(
                id,
                ts,
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage()
        );
        lock.writeLock().lock();
        try {
            if (entries.size() >= MAX_ENTRIES) {
                entries.remove(0);
            }
            entries.add(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the current maximum log entry id (0 if no entries yet).
     * The client stores this on page load and uses it as the {@code after}
     * parameter for subsequent polls.
     */
    public long currentMaxId() {
        return counter.get();
    }

    /**
     * Returns all entries with {@code id > afterId}, up to {@code limit}.
     */
    public List<LogEntry> entriesAfter(long afterId, int limit) {
        lock.readLock().lock();
        try {
            // Binary-search start index
            int lo = 0, hi = entries.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (entries.get(mid).id() <= afterId) lo = mid + 1;
                else hi = mid;
            }
            int end = Math.min(lo + limit, entries.size());
            return new ArrayList<>(entries.subList(lo, end));
        } finally {
            lock.readLock().unlock();
        }
    }

    public record LogEntry(long id, String timestamp, String level, String logger, String message) {}
}
