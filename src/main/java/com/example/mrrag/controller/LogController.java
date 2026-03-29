package com.example.mrrag.controller;

import com.example.mrrag.logging.InMemoryLogAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Serves the log viewer page and a polling API.
 *
 * <ul>
 *   <li>{@code GET /logs}         — HTML page</li>
 *   <li>{@code GET /logs/current} — returns current max log id as plain text
 *       (called on page load to establish the baseline)</li>
 *   <li>{@code GET /logs/since?after={id}&limit={n}}
 *                                — JSON array of new entries since {@code id}</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/logs")
public class LogController {

    private static final int DEFAULT_LIMIT = 500;

    private final InMemoryLogAppender appender;

    /** Serves the single-page log viewer. */
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
                    body  { font-family: monospace; background:#111; color:#ccc;
                            margin:0; padding:8px; }
                    h2    { color:#eee; margin:0 0 6px; font-size:1rem; }
                    #ctrl { margin-bottom:6px; }
                    #ctrl button { margin-right:6px; padding:3px 8px;
                                   background:#333; color:#ccc;
                                   border:1px solid #555; cursor:pointer; }
                    #ctrl button:hover { background:#444; }
                    #log  { white-space:pre-wrap; word-break:break-all;
                            font-size:0.78rem; line-height:1.4; }
                    .TRACE{ color:#888; }
                    .DEBUG{ color:#8be; }
                    .INFO { color:#8e8; }
                    .WARN { color:#fe8; }
                    .ERROR{ color:#f66; font-weight:bold; }
                  </style>
                </head>
                <body>
                  <h2>Log viewer — entries since page load</h2>
                  <div id="ctrl">
                    <button onclick="clearLog()">Clear</button>
                    <button onclick="togglePause()">Pause</button>
                    <span id="status"></span>
                  </div>
                  <div id="log"></div>
                  <script>
                    let afterId = 0;
                    let paused  = false;
                    let timer;

                    // On load: get current max id so we only see NEW events
                    fetch('/logs/current')
                      .then(r => r.text())
                      .then(id => { afterId = parseInt(id); poll(); });

                    function poll() {
                      if (!paused) {
                        fetch('/logs/since?after=' + afterId)
                          .then(r => r.json())
                          .then(entries => {
                            if (entries.length) {
                              afterId = entries[entries.length - 1].id;
                              appendEntries(entries);
                            }
                          })
                          .catch(() => {});
                      }
                      timer = setTimeout(poll, 1500);
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
                        span.textContent =
                          e.timestamp + ' ' + e.level.padEnd(5)
                          + ' ' + logger + ' : ' + e.message + '\\n';
                        div.appendChild(span);
                      }
                      if (atBottom) window.scrollTo(0, document.body.scrollHeight);
                    }

                    function clearLog() {
                      document.getElementById('log').innerHTML = '';
                    }

                    function togglePause() {
                      paused = !paused;
                      document.querySelector('#ctrl button:nth-child(2)')
                        .textContent = paused ? 'Resume' : 'Pause';
                      document.getElementById('status')
                        .textContent = paused ? '(paused)' : '';
                    }
                  </script>
                </body>
                </html>
                """;
    }

    /** Returns current max log entry id as plain text. */
    @GetMapping(value = "/current", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String currentId() {
        return String.valueOf(appender.currentMaxId());
    }

    /** Returns new log entries after the given id. */
    @GetMapping(value = "/since", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<InMemoryLogAppender.LogEntry> since(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        return appender.entriesAfter(after, limit);
    }
}
