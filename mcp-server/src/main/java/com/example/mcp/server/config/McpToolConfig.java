package com.example.mcp.server.config;

import com.example.mcp.server.tool.DatabaseTools;
import com.example.mcp.server.tool.OrderTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 工具注册配置
 * 将 @Tool 注解的方法注册为 ToolCallbackProvider，
 * MCP Server 自动配置会将其转换为 MCP Tool Specification 暴露给 Client
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider databaseToolProvider(DatabaseTools databaseTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(databaseTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider orderToolProvider(OrderTools orderTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderTools)
                .build();
    }
}
