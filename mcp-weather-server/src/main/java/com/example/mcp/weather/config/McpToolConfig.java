package com.example.mcp.weather.config;

import com.example.mcp.weather.tool.WeatherTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Weather Server 工具注册配置
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider weatherToolProvider(WeatherTools weatherTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherTools)
                .build();
    }
}
