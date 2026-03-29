package com.example.mrrag.controller;

import com.example.mrrag.logging.InMemoryLogAppender;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Log viewer endpoints.
 *
 * <pre>
 *   GET  /logs              — HTML page
 *   POST /logs/register     — register consumer, returns current max id
 *   POST /logs/unregister   — unregister consumer (called on Stop / page unload)
 *   GET  /logs/since?after= — new entries since id
 * </pre>
 *
 * The appender instance is fetched via {@link InMemoryLogAppender#getInstance()}
 * (LoggerContext lookup) so there is exactly one instance shared between
 * Logback and this controller.
 */
@Controller
@RequestMapping("/logs")
public class LogController {

    private static final int DEFAULT_LIMIT = 500;

    private InMemoryLogAppender appender() {
        return InMemoryLogAppender.getInstance();
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String logsPage() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Logs — java-mr-rag</title>
                  <style>
                    body  { font-family: monospace; background: #111; color: #ccc;
                            margin: 0; padding: 8px; }
                    h2    { color: #eee; margin: 0 0 6px; font-size: 1rem; }
                    #ctrl { margin-bottom: 6px; }
                    #ctrl button { margin-right: 6px; padding: 3px 10px;
                                   background: #2a2a2a; color: #ccc;
                                   border: 1px solid #555; cursor: pointer; }
                    #ctrl button:hover { background: #3a3a3a; }
                    #ctrl button.danger { border-color: #a33; color: #f88; }
                    #status { font-size: 0.8rem; color: #888; }
                    #log  { white-space: pre-wrap; word-break: break-all;
                            font-size: 0.78rem; line-height: 1.45; }
                    .TRACE { color: #777; }
                    .DEBUG { color: #7bc; }
                    .INFO  { color: #7c7; }
                    .WARN  { color: #fa7; }
                    .ERROR { color: #f55; font-weight: bold; }
                  </style>
                </head>
                <body>
                  <h2>Log viewer — entries since page load</h2>
                  <div id="ctrl">
                    <button onclick="clearLog()">Clear</button>
                    <button id="btnStop" class="danger" onclick="stop()">Stop</button>
                    <span id="status"></span>
                  </div>
                  <div id="log"></div>

                  <script>
                    let afterId = 0;
                    let active  = true;
                    let timer   = null;

                    // Register consumer on the server; receive baseline id
                    fetch('/logs/register', { method: 'POST' })
                      .then(r => r.text())
                      .then(id => { afterId = parseInt(id, 10); scheduleNext(); });

                    // Unregister when tab is closed or navigated away
                    window.addEventListener('beforeunload', unregister);

                    function scheduleNext() {
                      if (active) timer = setTimeout(poll, 1500);
                    }

                    function poll() {
                      fetch('/logs/since?after=' + afterId)
                        .then(r => r.json())
                        .then(entries => {
                          if (entries.length) {
                            afterId = entries[entries.length - 1].id;
                            appendEntries(entries);
                          }
                          scheduleNext();
                        })
                        .catch(() => scheduleNext());
                    }

                    function appendEntries(entries) {
                      const div = document.getElementById('log');
                      const atBottom = (window.innerHeight + window.scrollY)
                                        >= document.body.offsetHeight - 40;
                      for (const e of entries) {
                        const span = document.createElement('span');
                        span.className = e.level;
                        const logger = e.logger.length > 40
                            ? '...' + e.logger.slice(-37) : e.logger;
                        span.textContent = e.timestamp + ' '
                            + e.level.padEnd(5) + ' ' + logger
                            + ' : ' + e.message + '\\n';
                        div.appendChild(span);
                      }
                      if (atBottom) window.scrollTo(0, document.body.scrollHeight);
                    }

                    function stop() {
                      active = false;
                      clearTimeout(timer);
                      unregister();
                      document.getElementById('btnStop').disabled = true;
                      document.getElementById('status').textContent =
                          'Stopped. Refresh the page to resume.';
                    }

                    function unregister() {
                      // sendBeacon is fire-and-forget, works during page unload
                      navigator.sendBeacon('/logs/unregister');
                    }

                    function clearLog() {
                      document.getElementById('log').innerHTML = '';
                    }
                  </script>
                </body>
                </html>
                """;
    }

    /** Register a new consumer; returns the current max id as plain text. */
    @PostMapping(value = "/register", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String register() {
        return String.valueOf(appender().registerConsumer());
    }

    /** Unregister a consumer (called on Stop or page unload). */
    @PostMapping("/unregister")
    @ResponseBody
    public void unregister() {
        appender().unregisterConsumer();
    }

    /** Returns new log entries after the given id. */
    @GetMapping(value = "/since", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<InMemoryLogAppender.LogEntry> since(
            @RequestParam(defaultValue = "0")  long after,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        return appender().entriesAfter(after, limit);
    }
}
