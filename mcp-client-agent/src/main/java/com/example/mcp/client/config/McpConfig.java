package com.example.mcp.client.config;

import org.springframework.context.annotation.Configuration;

/**
 * MCP 配置
 *
 * Spring AI 1.0.x 会自动给 MCP 工具名加前缀（如 agent_mcp_client_business_server_getWeather），
 * SkillExecutor 中已通过后缀匹配兼容此行为，SKILL.md 中只需写原始工具名（如 getWeather）即可。
 */
@Configuration
public class McpConfig {
}
