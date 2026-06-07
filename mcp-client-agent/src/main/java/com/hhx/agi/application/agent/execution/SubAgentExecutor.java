package com.hhx.agi.application.agent.execution;

import com.hhx.agi.application.agent.model.SkillDefinition;
import com.hhx.agi.application.agent.skill.SkillLoader;
import com.hhx.agi.application.agent.tool.SkillToolCallback;
import com.hhx.agi.application.agent.tool.ToolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claude Code 风格的同步子 Agent 执行器。
 *
 * 子 Agent 不继承主对话，只接收 Agent 工具传入的自包含 prompt；同时默认不暴露 Agent 工具，避免递归委派。
 */
@Component
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    private static final int MAX_SUB_AGENT_ROUNDS = 4;
    private static final int MAX_SUB_AGENT_TOOL_CALLS = 10;
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final int MAX_SUB_AGENT_OUTPUT_TOKENS = 1024;
    private static final String GENERAL_PURPOSE_AGENT = "worker";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ToolResolver toolResolver;
    private final SkillToolCallback skillToolCallback;
    private final SkillLoader skillLoader;

    public SubAgentExecutor(ObjectProvider<ChatModel> chatModelProvider, ToolResolver toolResolver,
                            SkillToolCallback skillToolCallback, SkillLoader skillLoader) {
        this.chatModelProvider = chatModelProvider;
        this.toolResolver = toolResolver;
        this.skillToolCallback = skillToolCallback;
        this.skillLoader = skillLoader;
    }

    public SubAgentResult execute(SubAgentRequest request) {
        long startTime = System.currentTimeMillis();
        String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        ResolvedSubAgent resolved = resolveSubAgent(request.subagentType());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(resolved)));
        messages.add(new UserMessage(request.prompt()));

        ToolCallback[] activeTools = resolved.tools();
        Map<String, ToolCallback> activeToolMap = toToolMap(activeTools);
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("agentId", agentId);
        toolContext.put("agentType", resolved.agentType());

        Set<String> calledSignatures = new LinkedHashSet<>();
        int totalToolCalls = 0;
        int consecutiveFailures = 0;

        for (int round = 1; round <= MAX_SUB_AGENT_ROUNDS; round++) {
            ChatResponse response = chatModelProvider.getObject()
                    .call(new Prompt(messages, buildOptions(request.model(), activeTools, toolContext)));
            AssistantMessage assistant = response.getResult().getOutput();

            if (!assistant.hasToolCalls()) {
                messages.add(assistant);
                String content = assistant.getText();
                if (content == null || content.isBlank()) {
                    content = "子 Agent 已完成，但模型未返回有效文本。";
                }
                return new SubAgentResult(agentId, resolved.agentType(), content, totalToolCalls,
                        System.currentTimeMillis() - startTime);
            }

            StringBuilder observations = new StringBuilder("## 子 Agent 工具执行结果\n");
            List<Message> pendingContextMessages = new ArrayList<>();

            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                totalToolCalls++;
                if (totalToolCalls > MAX_SUB_AGENT_TOOL_CALLS) {
                    return stoppedResult(agentId, resolved.agentType(), totalToolCalls, startTime,
                            "子 Agent 工具调用次数过多，已停止以避免循环调用。");
                }

                ToolCallback tool = activeToolMap.get(toolCall.name());
                String signature = toolCall.name() + ":" + toolCall.arguments();
                String result;

                if (calledSignatures.contains(signature)) {
                    result = "执行失败: 检测到重复工具调用，已阻断。";
                } else if (tool == null) {
                    result = "执行失败: 工具不可用: " + toolCall.name();
                } else {
                    calledSignatures.add(signature);
                    try {
                        result = tool.call(toolCall.arguments(), new ToolContext(toolContext));
                    } catch (Exception e) {
                        result = "执行失败: " + AgentErrorFormatter.userFacing(e);
                    }
                }

                if (isFailure(result)) {
                    consecutiveFailures++;
                } else {
                    consecutiveFailures = 0;
                }

                SkillDefinition loaded = loadedSkillFromToolResult(toolCall.name(), result);
                if (loaded != null) {
                    activeTools = concat(skillToolCallback, toolResolver.resolveTools(loaded));
                    activeToolMap = toToolMap(activeTools);
                    pendingContextMessages.add(new SystemMessage(buildLoadedSkillPrompt(loaded, result)));
                }

                observations.append("- 工具: ").append(toolCall.name()).append("\n")
                        .append("  参数: ").append(toolCall.arguments()).append("\n")
                        .append("  结果: ").append(result).append("\n");
            }

            messages.add(new UserMessage(observations
                    + "\n请基于以上工具结果继续判断下一步：继续调用工具，或返回给主 Agent 的最终结果。"
                    + "如果信息不足，请说明缺少哪些信息，不要臆造。"));
            messages.addAll(pendingContextMessages);

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                return stoppedResult(agentId, resolved.agentType(), totalToolCalls, startTime,
                        "子 Agent 连续工具调用失败，已停止。请主 Agent 根据已有信息决定是否追问用户。");
            }
        }

        return stoppedResult(agentId, resolved.agentType(), totalToolCalls, startTime,
                "子 Agent 执行轮次过多，已停止以避免循环调用。");
    }

    public String renderAvailableSubAgents() {
        String skills = skillToolCallback.skills().stream()
                .map(skill -> "- " + skill.name() + ": " + skill.description())
                .collect(Collectors.joining("\n"));
        if (skills.isBlank()) {
            return "- worker: 通用子 Agent，可使用 SkillTool 和 MCP 工具处理自包含任务";
        }
        return "- worker: 通用子 Agent，可使用 SkillTool 和 MCP 工具处理自包含任务\n" + skills;
    }

    private ResolvedSubAgent resolveSubAgent(String subagentType) {
        String normalized = normalizeAgentType(subagentType);
        if (normalized == null || isGeneralPurpose(normalized)) {
            ToolCallback[] mcpTools = safeAllMcpTools();
            return new ResolvedSubAgent(GENERAL_PURPOSE_AGENT, null, concat(skillToolCallback, mcpTools));
        }

        SkillDefinition skill = skillToolCallback.findSkill(normalized);
        if (skill == null) {
            throw new IllegalArgumentException("未知子 Agent 类型: " + subagentType + "。可用类型: worker, "
                    + skillToolCallback.skills().stream().map(SkillDefinition::name).collect(Collectors.joining(", ")));
        }

        return new ResolvedSubAgent(skill.name(), skill, toolResolver.resolveTools(skill));
    }

    private boolean isGeneralPurpose(String agentType) {
        return GENERAL_PURPOSE_AGENT.equals(agentType)
                || "general".equals(agentType)
                || "general-purpose".equals(agentType)
                || "general_purpose".equals(agentType);
    }

    private String normalizeAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return null;
        }
        String normalized = agentType.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase();
    }

    private ToolCallingChatOptions buildOptions(String model, ToolCallback[] tools, Map<String, Object> toolContext) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .toolContext(toolContext)
                .internalToolExecutionEnabled(false)
                .temperature(0.1)
                .maxTokens(MAX_SUB_AGENT_OUTPUT_TOKENS);
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        return builder.build();
    }

    private String buildSystemPrompt(ResolvedSubAgent resolved) {
        if (resolved.skill() != null) {
            String prompt = skillLoader.loadPrompt(resolved.skill());
            return """
                    你是一个 Claude Code 风格的同步子 Agent，类型为 %s。

                    你看不到主 Agent 与用户的完整对话，只能依赖当前传入的任务 prompt。
                    你应该独立完成任务，并把结果返回给主 Agent；该结果不会直接展示给用户。
                    如果信息不足，请清楚说明缺少什么信息，不要向用户直接追问，不要编造。
                    输出必须简洁，优先控制在 300 字以内。

                    Skill 描述: %s
                    Skill Prompt:
                    %s
                    """.formatted(resolved.agentType(), resolved.skill().description(), prompt);
        }

        return """
                你是一个 Claude Code 风格的通用同步子 Agent。

                你看不到主 Agent 与用户的完整对话，只能依赖当前传入的任务 prompt。
                你应该独立完成复杂、多步骤、需要调研或验证的任务，并把结果返回给主 Agent。
                你的结果不会直接展示给用户；主 Agent 会综合后再回复用户。
                输出必须简洁，优先控制在 300 字以内。

                工具使用规则：
                - 如果任务匹配某个 Skill，可以先调用 SkillTool 加载该 Skill。
                - 如果 MCP 工具足以完成任务，可以直接调用 MCP 工具。
                - 不要使用 Agent 工具继续委派；你当前已经是子 Agent。
                - 如果信息不足，请清楚说明缺少什么信息，不要向用户直接追问，不要编造。
                """;
    }

    private String buildLoadedSkillPrompt(SkillDefinition skill, String skillToolResult) {
        return """
                当前子 Agent 已加载 Skill: %s
                Skill 描述: %s
                SkillTool 返回:
                %s

                后续请遵循该 Skill prompt，并优先使用 allowedTools 中的 MCP 工具完成任务。
                如果信息不足，请在最终结果里说明缺少什么信息，不要编造。
                """.formatted(skill.name(), skill.description(), skillToolResult);
    }

    private ToolCallback[] safeAllMcpTools() {
        try {
            return toolResolver.getAllTools().values().toArray(new ToolCallback[0]);
        } catch (Exception e) {
            log.warn("[SubAgent] 获取 MCP 工具失败，仅启用 SkillTool: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }

    private ToolCallback[] concat(ToolCallback first, ToolCallback[] rest) {
        ToolCallback[] result = new ToolCallback[(rest == null ? 0 : rest.length) + 1];
        result[0] = first;
        if (rest != null && rest.length > 0) {
            System.arraycopy(rest, 0, result, 1, rest.length);
        }
        return result;
    }

    private Map<String, ToolCallback> toToolMap(ToolCallback[] tools) {
        return Arrays.stream(tools)
                .collect(Collectors.toMap(tool -> tool.getToolDefinition().name(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private boolean isFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        String text = result.toLowerCase();
        return text.startsWith("执行失败:") || text.contains("\"success\":false") || text.contains("exception");
    }

    private SkillDefinition loadedSkillFromToolResult(String toolName, String result) {
        if (skillToolCallback.isSkillTool(toolName)) {
            return skillToolCallback.loadedSkillFromResult(result);
        }
        return null;
    }

    private SubAgentResult stoppedResult(String agentId, String agentType, int totalToolCalls, long startTime, String content) {
        return new SubAgentResult(agentId, agentType, content, totalToolCalls, System.currentTimeMillis() - startTime);
    }

    private record ResolvedSubAgent(String agentType, SkillDefinition skill, ToolCallback[] tools) {
    }

    public record SubAgentRequest(String description, String subagentType, String prompt, String model) {
    }

    public record SubAgentResult(String agentId, String agentType, String content, int totalToolUseCount,
                                 long totalDurationMs) {
    }
}
