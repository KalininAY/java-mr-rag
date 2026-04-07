package com.example.mrrag.app.controller;

import com.example.mrrag.app.config.AppConfig;
import com.example.mrrag.app.logging.InMemoryLogAppender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Log viewer endpoints.
 */
@Controller
@RequestMapping("/logs")
@Tag(name = "Логи", description = "Просмотр логов приложения в реальном времени через long-polling")
public class LogController {

    @Autowired
    AppConfig config;
    private InMemoryLogAppender appender() {
        return InMemoryLogAppender.getInstance();
    }

    @Operation(
        summary = "HTML-страница просмотра логов",
        description = "Возвращает HTML-страницу с live-просмотром логов приложения. Обновляется автоматически через polling каждую секунду.",
        responses = @ApiResponse(responseCode = "200", description = "HTML-страница",
            content = @Content(mediaType = MediaType.TEXT_HTML_VALUE, schema = @Schema(type = "string")))
    )
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
                    #ctrl button:hover  { background: #3a3a3a; }
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

                    fetch('/logs/register', { method: 'POST' })
                      .then(r => r.text())
                      .then(id => { afterId = parseInt(id, 10); scheduleNext(); });

                    window.addEventListener('beforeunload', unregister);

                    function scheduleNext() {
                      if (active) timer = setTimeout(poll, 1000);
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

    @Operation(
        summary = "Зарегистрировать потребителя логов",
        description = "Регистрирует нового потребителя логов и возвращает текущий максимальный ID записи. Используется как стартовый ID для последующих запросов /logs/since.",
        responses = @ApiResponse(responseCode = "200", description = "Текущий максимальный ID записи лога",
            content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "integer", example = "42")))
    )
    @PostMapping(value = "/register", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String register() {
        return String.valueOf(appender().registerConsumer());
    }

    @Operation(
        summary = "Снять регистрацию потребителя логов",
        description = "Снимает регистрацию текущего потребителя. Вызывается при закрытии страницы.",
        responses = @ApiResponse(responseCode = "200", description = "Потребитель успешно снят с регистрации")
    )
    @PostMapping("/unregister")
    @ResponseBody
    public void unregister() {
        appender().unregisterConsumer();
    }

    @Operation(
        summary = "Получить новые записи лога",
        description = "Возвращает все записи лога с ID строго больше указанного. Используется для инкрементального обновления страницы логов.",
        parameters = @Parameter(
            name = "after",
            description = "ID последней полученной записи. Вернутся только записи с ID > after",
            example = "42"
        ),
        responses = @ApiResponse(responseCode = "200", description = "Список новых записей лога")
    )
    @GetMapping(value = "/since", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<InMemoryLogAppender.LogEntry> since(
            @RequestParam(defaultValue = "0") long after
    ) {
        return appender().entriesAfter(after);
    }
}
