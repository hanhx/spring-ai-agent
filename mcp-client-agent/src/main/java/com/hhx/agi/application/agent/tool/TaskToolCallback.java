package com.hhx.agi.application.agent.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

@Component
public class TaskToolCallback implements ToolCallback {

    private final AgentToolCallback agentToolCallback;
    private final ToolDefinition toolDefinition;

    public TaskToolCallback(AgentToolCallback agentToolCallback) {
        this.agentToolCallback = agentToolCallback;
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(AgentToolCallback.LEGACY_TOOL_NAME)
                .description("Legacy alias for Agent. Launch a sub-agent for complex, self-contained tasks.")
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
        return agentToolCallback.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return agentToolCallback.call(toolInput, toolContext);
    }
}
