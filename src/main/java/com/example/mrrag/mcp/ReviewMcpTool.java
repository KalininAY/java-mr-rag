package com.example.mrrag.mcp;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.ReviewService;
import com.example.mrrag.review.model.ReviewContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tool «review_mr» — регистрируется Spring AI MCP Server
 * и доступен LLM-агентам через SSE-транспорт на {@code /sse}.
 *
 * <p>Агент передаёт три параметра; сервис строит граф (или берёт из кэша)
 * и возвращает обогащённый контекст ревью.
 */
@Component
@RequiredArgsConstructor
public class ReviewMcpTool {

    private final ReviewService reviewService;

    @Tool(name = "review_mr",
          description = """
                  Построить контекст code review для Merge Request в GitLab.
                  Возвращает список групп изменений, обогащённых AST-контекстом
                  (зависимости классов, вызовы методов, иерархия типов).
                  Используй этот инструмент когда нужно проанализировать MR.
                  """)
    public ReviewContext reviewMr(
            @ToolParam(description = "GitLab namespace, например group/subgroup") String namespace,
            @ToolParam(description = "Название репозитория в GitLab")             String repo,
            @ToolParam(description = "Внутренний ID Merge Request (mrIid)")       Long mrIid
    ) {
        return reviewService.buildReviewContext(new ReviewRequest(namespace, repo, mrIid));
    }
}
