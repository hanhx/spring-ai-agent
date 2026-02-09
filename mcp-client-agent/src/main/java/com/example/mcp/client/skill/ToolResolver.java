package com.example.mcp.client.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import com.example.mcp.client.config.McpConnectionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具解析器 —— 负责从 MCP 注册的工具中匹配 Skill 声明的工具
 *
 * 支持精确匹配和后缀匹配（兼容 Spring AI 自动添加的前缀）
 */
@Component
public class ToolResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolResolver.class);

    private final McpConnectionManager mcpTools;

    public ToolResolver(McpConnectionManager mcpTools) {
        this.mcpTools = mcpTools;
    }

    /**
     * 获取所有 MCP 工具（延迟加载）
     */
    public Map<String, ToolCallback> getAllTools() {
        return Arrays.stream(mcpTools.getToolCallbacks())
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        Function.identity()
                ));
    }

    /**
     * 根据 Skill 声明的工具名列表，从 MCP 注册的工具中匹配
     */
    public ToolCallback[] resolveTools(SkillDefinition skill) {
        Map<String, ToolCallback> allTools = getAllTools();
        List<ToolCallback> matched = new ArrayList<>();
        for (String toolName : skill.allowedTools()) {
            ToolCallback tool = findTool(allTools, toolName);
            if (tool != null) {
                matched.add(tool);
            } else {
                log.warn("[ToolResolver] Skill [{}] 声明的工具 '{}' 未找到", skill.name(), toolName);
            }
        }
        return matched.toArray(new ToolCallback[0]);
    }

    /**
     * 格式化工具签名（含参数名、类型、required），供 Planner 判断参数是否齐全
     */
    public String formatToolSignatures(ToolCallback[] tools) {
        if (tools == null || tools.length == 0) return "无";
        StringBuilder sb = new StringBuilder();
        for (ToolCallback tool : tools) {
            var def = tool.getToolDefinition();
            sb.append("- ").append(def.name()).append(": ").append(def.description()).append("\n");
            String schema = def.inputSchema();
            if (schema != null && !schema.isBlank()) {
                sb.append("  参数 schema: ").append(schema).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 根据短名称匹配工具（精确匹配 + 后缀匹配）
     */
    private ToolCallback findTool(Map<String, ToolCallback> allTools, String shortName) {
        if (allTools.containsKey(shortName)) {
            return allTools.get(shortName);
        }
        String suffix = "_" + shortName;
        for (Map.Entry<String, ToolCallback> entry : allTools.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
