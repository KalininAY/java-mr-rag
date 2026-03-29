package com.example.mrrag.logging;

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
 * <p>
 * The single instance created by Logback is retrieved at runtime through
 * {@link #getInstance()} via {@link LoggerContext} — no Spring {@code @Component},
 * no duplicate instances.
 * <p>
 * The buffer is <b>active only while at least one consumer is registered</b>.
 * Consumers call {@link #registerConsumer()} on page-open and
 * {@link #unregisterConsumer()} on page-close (via {@code /logs/unregister}).
 * When the consumer count drops to zero the buffer is cleared and further
 * appending is skipped until a new consumer arrives.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    public static final int MAX_ENTRIES = 10_000;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final AtomicLong    counter   = new AtomicLong(0);
    private final AtomicInteger consumers = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<LogEntry> entries  = new ArrayList<>(1024);

    // -----------------------------------------------------------------------
    // Static accessor — returns the ONE instance owned by Logback
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

    /** Called when a browser page opens the log viewer. */
    public long registerConsumer() {
        consumers.incrementAndGet();
        // Return current max id so the client only sees future events
        return counter.get();
    }

    /** Called when the browser page is closed or the user clicks Stop. */
    public void unregisterConsumer() {
        if (consumers.decrementAndGet() <= 0) {
            consumers.set(0);
            clearBuffer();
        }
    }

    private void clearBuffer() {
        lock.writeLock().lock();
        try {
            entries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Logback append
    // -----------------------------------------------------------------------

    @Override
    protected void append(ILoggingEvent event) {
        // Skip buffering when nobody is watching
        if (consumers.get() <= 0) return;

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
            if (entries.size() >= MAX_ENTRIES) entries.remove(0);
            entries.add(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Query API
    // -----------------------------------------------------------------------

    /** Returns entries with {@code id > afterId}, up to {@code limit}. */
    public List<LogEntry> entriesAfter(long afterId, int limit) {
        if (consumers.get() <= 0) return Collections.emptyList();
        lock.readLock().lock();
        try {
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

    public record LogEntry(long id, String timestamp, String level,
                           String logger, String message) {}
}
