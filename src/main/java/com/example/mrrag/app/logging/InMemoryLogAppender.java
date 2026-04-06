package com.example.mrrag.app.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Logback appender wired via {@code logback-spring.xml}.
 *
 * <h2>Buffer strategy</h2>
 * Entries older than {@value #MAX_AGE_MS} ms are evicted on every
 * {@link #append} call. With a client poll interval of 1 s this means
 * the buffer holds at most ~5 s worth of log lines — enough to survive a
 * brief network hiccup, but never accumulates unboundedly.
 *
 * <h2>Lifecycle</h2>
 * Buffering is active only while {@code consumers > 0}.
 * Managed via {@link #registerConsumer()} / {@link #unregisterConsumer()}.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    /** How long to keep entries in the buffer (milliseconds). */
    public static final long MAX_AGE_MS = 5_000;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final AtomicLong    counter   = new AtomicLong(0);
    private final AtomicInteger consumers = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** Entries sorted by id (and time) ascending. */
    private final List<LogEntry> entries  = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Static accessor — the ONE instance owned by Logback
    // -----------------------------------------------------------------------

    public static InMemoryLogAppender getInstance() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        InMemoryLogAppender appender =
                (InMemoryLogAppender) root.getAppender("MEMORY");
        if (appender == null) {
            throw new IllegalStateException(
                    "InMemoryLogAppender 'MEMORY' not found in Logback root logger. "
                  + "Check logback-spring.xml.");
        }
        return appender;
    }

    // -----------------------------------------------------------------------
    // Consumer lifecycle
    // -----------------------------------------------------------------------

    /** Called when a browser opens the log viewer. Returns current max id as baseline. */
    public long registerConsumer() {
        consumers.incrementAndGet();
        return counter.get();
    }

    /** Called on Stop / page unload. Clears buffer when no consumers remain. */
    public void unregisterConsumer() {
        if (consumers.decrementAndGet() <= 0) {
            consumers.set(0);
            lock.writeLock().lock();
            try { entries.clear(); } finally { lock.writeLock().unlock(); }
        }
    }

    // -----------------------------------------------------------------------
    // Logback append
    // -----------------------------------------------------------------------

    @Override
    protected void append(ILoggingEvent event) {
        if (consumers.get() <= 0) return;

        long now  = System.currentTimeMillis();
        long id   = counter.incrementAndGet();
        String ts = TS_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        LogEntry entry = new LogEntry(
                id, now, ts,
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage()
        );

        lock.writeLock().lock();
        try {
            entries.add(entry);
            long cutoff = now - MAX_AGE_MS;
            int  drop   = 0;
            while (drop < entries.size() && entries.get(drop).epochMs() < cutoff) drop++;
            if (drop > 0) entries.subList(0, drop).clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Query API
    // -----------------------------------------------------------------------

    /** Returns all entries with {@code id > afterId}. */
    public List<LogEntry> entriesAfter(long afterId) {
        if (consumers.get() <= 0) return Collections.emptyList();
        lock.readLock().lock();
        try {
            int lo = 0, hi = entries.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (entries.get(mid).id() <= afterId) lo = mid + 1;
                else hi = mid;
            }
            return new ArrayList<>(entries.subList(lo, entries.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Internal record — {@code epochMs} is used for eviction and not sent to clients. */
    public record LogEntry(
            long   id,
            long   epochMs,
            String timestamp,
            String level,
            String logger,
            String message
    ) {}
}
