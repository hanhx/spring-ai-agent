package com.hhx.agi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Client Agent 启动类
 * 通过 MCP 协议连接 MCP Server，结合 LLM 做智能决策
 */
@SpringBootApplication(scanBasePackages = "com.hhx.agi")
public class McpClientAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientAgentApplication.class, args);
    }
}
