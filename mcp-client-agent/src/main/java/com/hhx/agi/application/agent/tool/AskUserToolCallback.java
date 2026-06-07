package com.hhx.agi.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

@Component
public class AskUserToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "AskUser";

    private static final Logger log = LoggerFactory.getLogger(AskUserToolCallback.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolDefinition toolDefinition = DefaultToolDefinition.builder()
            .name(TOOL_NAME)
            .description("Ask the user for missing information required to continue the task.")
            .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "question": {"type": "string", "description": "A concise question asking for the missing information"}
                      },
                      "required": ["question"]
                    }
                    """)
            .build();

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
        return toolInput;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    public boolean isAskUserTool(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public String extractQuestion(String toolInput) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String question = readText(root, "question");
            if (question == null) {
                question = readText(root, "message");
            }
            if (question != null && !question.isBlank()) {
                return question.trim();
            }
        } catch (Exception e) {
            log.warn("[AskUserTool] 参数解析失败: {}", e.getMessage());
        }
        return "请补充完成该任务所需的信息。";
    }

    public boolean isLikelyAskUserResponse(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim();
        if (normalized.length() > 300 || normalized.contains("还有其他")) {
            return false;
        }
        return normalized.contains("请提供")
                || normalized.contains("请补充")
                || normalized.contains("请告知")
                || normalized.contains("请确认")
                || normalized.contains("请问您想")
                || normalized.contains("需要您提供")
                || normalized.contains("哪个城市")
                || normalized.contains("订单号")
                || normalized.contains("退款原因")
                || normalized.contains("PDF文件路径")
                || normalized.contains("pdf文件路径");
    }

    private String readText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
