package com.hhx.agi.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhx.agi.application.agent.execution.AgentErrorFormatter;
import com.hhx.agi.application.agent.model.SkillDefinition;
import com.hhx.agi.application.agent.skill.SkillLoader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SkillToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "SkillTool";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SkillLoader skillLoader;
    private final Map<String, SkillDefinition> skillMap;
    private final ToolDefinition toolDefinition;

    public SkillToolCallback(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
        this.skillMap = skillLoader.getSkills().stream()
                .collect(Collectors.toMap(SkillDefinition::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Load a named skill before handling a task that matches that skill. The skill provides task-specific instructions and allowed MCP tools.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "skillName": {"type": "string", "description": "The skill name to load"},
                            "args": {"type": "string", "description": "Optional user arguments or sub-task for the skill"}
                          },
                          "required": ["skillName"]
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
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String skillName = readText(root, "skillName");
            if (skillName == null) {
                skillName = readText(root, "skill");
            }
            if (skillName == null || skillName.isBlank()) {
                return error("缺少 skillName 参数");
            }

            String normalized = normalizeSkillName(skillName);
            SkillDefinition skill = skillMap.get(normalized);
            if (skill == null) {
                return error("未知 Skill: " + skillName + "。可用 Skills: " + String.join(", ", skillMap.keySet()));
            }

            String prompt = skillLoader.loadPrompt(skill);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("skillName", skill.name());
            result.put("description", skill.description());
            result.put("allowedTools", skill.allowedTools());
            result.put("prompt", prompt);
            result.put("message", "Skill 已加载，请严格遵循 prompt，并只使用 allowedTools 中的 MCP 工具。若信息不足，直接追问用户。");
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return error("SkillTool 执行失败: " + AgentErrorFormatter.userFacing(e));
        }
    }

    public SkillDefinition loadedSkillFromResult(String toolResult) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolResult == null || toolResult.isBlank() ? "{}" : toolResult);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            String skillName = readText(root, "skillName");
            return findSkill(skillName);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isSkillTool(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public String toDisplayResult(String toolResult) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolResult == null || toolResult.isBlank() ? "{}" : toolResult);
            boolean success = root.path("success").asBoolean(false);
            if (success) {
                String skillName = readText(root, "skillName");
                String description = readText(root, "description");
                if (description != null && !description.isBlank()) {
                    return "Skill 已加载: " + skillName + " - " + description;
                }
                return "Skill 已加载: " + skillName;
            }
            String error = readText(root, "error");
            return error == null || error.isBlank() ? "Skill 加载失败。" : "Skill 加载失败: " + error;
        } catch (Exception ignored) {
            return toolResult;
        }
    }

    public String renderAvailableSkills() {
        if (skillMap.isEmpty()) {
            return "无";
        }
        return skillMap.values().stream()
                .map(skill -> "- " + skill.name() + ": " + skill.description())
                .collect(Collectors.joining("\n"));
    }

    public SkillDefinition findSkill(String skillName) {
        if (skillName == null) {
            return null;
        }
        return skillMap.get(normalizeSkillName(skillName));
    }

    public List<SkillDefinition> skills() {
        return List.copyOf(skillMap.values());
    }

    private String normalizeSkillName(String skillName) {
        String normalized = skillName.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase();
    }

    private String readText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String error(String message) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("success", false, "error", message));
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"" + message + "\"}";
        }
    }
}
