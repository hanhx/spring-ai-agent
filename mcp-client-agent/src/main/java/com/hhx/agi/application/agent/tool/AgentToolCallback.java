package com.hhx.agi.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhx.agi.application.agent.execution.AgentErrorFormatter;
import com.hhx.agi.application.agent.execution.SubAgentExecutor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "Agent";
    public static final String LEGACY_TOOL_NAME = "Task";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SubAgentExecutor subAgentExecutor;
    private final ToolDefinition toolDefinition;

    public AgentToolCallback(SubAgentExecutor subAgentExecutor) {
        this.subAgentExecutor = subAgentExecutor;
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        Launch a Claude Code style sub-agent for complex, multi-step, self-contained tasks.
                        The sub-agent cannot see the parent conversation, so prompt must include all required context.
                        Available subagent_type values are worker or any loaded Skill name.
                        """)
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "description": {"type": "string", "description": "A short 3-5 word description of the task"},
                            "subagent_type": {"type": "string", "description": "Optional sub-agent type. Use worker for general purpose, or a Skill name for a specialist."},
                            "prompt": {"type": "string", "description": "A self-contained task prompt for the sub-agent"}
                          },
                          "required": ["description", "prompt"]
                        }
                        """)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String description = readText(root, "description");
            String prompt = readText(root, "prompt");
            String subagentType = readText(root, "subagent_type");
            if (subagentType == null) {
                subagentType = readText(root, "subagentType");
            }

            if (description == null || description.isBlank()) {
                return error("缺少 description 参数");
            }
            if (prompt == null || prompt.isBlank()) {
                return error("缺少 prompt 参数");
            }

            String model = readContextText(toolContext, "model");
            SubAgentExecutor.SubAgentResult result = subAgentExecutor.execute(
                    new SubAgentExecutor.SubAgentRequest(description.trim(), subagentType, prompt.trim(), model));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("status", "completed");
            payload.put("agentId", result.agentId());
            payload.put("agentType", result.agentType());
            payload.put("description", description.trim());
            payload.put("content", result.content());
            payload.put("totalToolUseCount", result.totalToolUseCount());
            payload.put("totalDurationMs", result.totalDurationMs());
            payload.put("message", "子 Agent 已完成。该结果是内部观察，主 Agent 需要总结后再回复用户。");
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return error("子 Agent 执行失败: " + AgentErrorFormatter.userFacing(e));
        }
    }

    public boolean isAgentTool(String toolName) {
        return TOOL_NAME.equals(toolName) || LEGACY_TOOL_NAME.equals(toolName);
    }

    public String toDisplayResult(String toolResult) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolResult == null || toolResult.isBlank() ? "{}" : toolResult);
            if (!root.path("success").asBoolean(false)) {
                String error = readText(root, "error");
                if (error == null || error.isBlank()) {
                    return "子 Agent 执行失败。";
                }
                if (error.startsWith("子 Agent 执行失败")) {
                    return error;
                }
                return "子 Agent 执行失败: " + error;
            }

            String agentType = readText(root, "agentType");
            String description = readText(root, "description");
            String content = readText(root, "content");
            StringBuilder display = new StringBuilder("子 Agent 已完成");
            if (agentType != null && !agentType.isBlank()) {
                display.append(" [").append(agentType).append("]");
            }
            if (description != null && !description.isBlank()) {
                display.append(": ").append(description);
            }
            if (content != null && !content.isBlank()) {
                display.append("\n").append(content);
            }
            return display.toString();
        } catch (Exception ignored) {
            return toolResult;
        }
    }

    public String renderAvailableAgents() {
        return subAgentExecutor.renderAvailableSubAgents();
    }

    private String readText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String readContextText(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    private String error(String message) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("success", false, "error", message));
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"" + message + "\"}";
        }
    }
}
