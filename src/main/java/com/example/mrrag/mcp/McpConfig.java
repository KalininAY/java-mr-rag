package com.example.mrrag.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует MCP-инструменты в Spring AI MCP Server.
 *
 * <p>SSE endpoint доступен по адресу {@code GET /sse}.
 * Сообщения агента принимаются на {@code POST /mcp/messages}.
 *
 * <p>Пример конфигурации в Claude Desktop / Cursor:
 * <pre>
 * {
 *   "mcpServers": {
 *     "mr-rag": {
 *       "transport": "sse",
 *       "url": "http://localhost:8080/sse"
 *     }
 *   }
 * }
 * </pre>
 */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider reviewToolCallbackProvider(ReviewMcpTool reviewMcpTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(reviewMcpTool)
                .build();
    }
}
