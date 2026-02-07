package com.example.mcp.weather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Weather Server 启动类
 * 通过 SSE 协议暴露天气查询工具
 */
@SpringBootApplication
public class McpWeatherServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpWeatherServerApplication.class, args);
    }
}
