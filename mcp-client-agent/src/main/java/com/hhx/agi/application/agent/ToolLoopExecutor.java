package com.hhx.agi.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
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
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopExecutor.class);

    private static final int MAX_TOOL_LOOP_ROUNDS = 8;
    private static final int MAX_TOOL_CALLS = 20;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String ASK_USER_TOOL_NAME = "AskUser";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ToolResolver toolResolver;
    private final SkillToolCallback skillToolCallback;
    private final ToolCallback askUserToolCallback = new AskUserToolCallback();

    public ToolLoopExecutor(ChatModel chatModel, ChatMemory chatMemory, ToolResolver toolResolver,
                            SkillToolCallback skillToolCallback) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.toolResolver = toolResolver;
        this.skillToolCallback = skillToolCallback;
    }

    public Flux<PlanActionEvent> execute(String conversationId, String userMessage, String model, String userId) {
        return Flux.defer(() -> Mono.fromCallable(() -> executeBlocking(conversationId, userMessage, model, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable))
                .onErrorResume(e -> {
                    log.error("[ToolLoop] 执行失败: {}", e.getMessage(), e);
                    return Flux.just(PlanActionEvent.error("执行出错: " + e.getMessage()), PlanActionEvent.done());
                });
    }

    private List<PlanActionEvent> executeBlocking(String conversationId, String userMessage, String model, String userId) {
        com.hhx.agi.infra.config.UserContext.setUserId(userId);
        try {
            List<PlanActionEvent> events = new ArrayList<>();
            List<Message> messages = new ArrayList<>();
            chatMemory.add(conversationId, new UserMessage(userMessage));

            messages.add(new SystemMessage(buildSystemPrompt()));
            messages.add(new UserMessage(enrichWithHistory(conversationId, userMessage)));
            events.add(PlanActionEvent.planning("🤔 正在分析可用 Skill 与 MCP 工具..."));

            ToolCallback[] allMcpTools = safeAllMcpTools();
            ToolCallback[] activeTools = concat(skillToolCallback, askUserToolCallback, allMcpTools);
            Map<String, ToolCallback> activeToolMap = toToolMap(activeTools);
            Map<String, Object> toolContext = new LinkedHashMap<>();
            Set<String> calledSignatures = new LinkedHashSet<>();

            int totalToolCalls = 0;
            int consecutiveFailures = 0;
            SkillDefinition loadedSkill = null;

            for (int round = 1; round <= MAX_TOOL_LOOP_ROUNDS; round++) {
                ChatResponse response = chatModel.call(new Prompt(messages, buildOptions(normalizeModel(model), activeTools, toolContext)));
                AssistantMessage assistant = response.getResult().getOutput();

                if (!assistant.hasToolCalls()) {
                    messages.add(assistant);
                    String finalAnswer = assistant.getText();
                    if (finalAnswer == null || finalAnswer.isBlank()) {
                        finalAnswer = "已完成处理，但模型未返回有效文本。";
                    }
                    chatMemory.add(conversationId, new AssistantMessage(finalAnswer));
                    if (isLikelyAskUserResponse(finalAnswer)) {
                        events.add(PlanActionEvent.askUser(finalAnswer));
                    }
                    events.add(PlanActionEvent.result(finalAnswer));
                    events.add(PlanActionEvent.done());
                    return events;
                }

                StringBuilder observations = new StringBuilder("## 工具执行结果\n");
                List<Message> pendingContextMessages = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    totalToolCalls++;
                    if (totalToolCalls > MAX_TOOL_CALLS) {
                        String msg = "工具调用次数过多，已停止以避免循环调用。";
                        chatMemory.add(conversationId, new AssistantMessage(msg));
                        events.add(PlanActionEvent.error(msg));
                        events.add(PlanActionEvent.done());
                        return events;
                    }

                    ToolCallback tool = activeToolMap.get(toolCall.name());
                    String signature = toolCall.name() + ":" + toolCall.arguments();
                    String result;
                    events.add(PlanActionEvent.actionStart(totalToolCalls, MAX_TOOL_CALLS, toolCall.name()));

                    if (ASK_USER_TOOL_NAME.equals(toolCall.name())) {
                        result = extractAskUserQuestion(toolCall.arguments());
                        events.add(PlanActionEvent.actionDone(totalToolCalls, MAX_TOOL_CALLS, toolCall.name(), result));
                        chatMemory.add(conversationId, new AssistantMessage(result));
                        events.add(PlanActionEvent.askUser(result));
                        events.add(PlanActionEvent.result(result));
                        events.add(PlanActionEvent.done());
                        return events;
                    }

                    if (calledSignatures.contains(signature)) {
                        result = "执行失败: 检测到重复工具调用，已阻断。";
                    } else if (tool == null) {
                        result = "执行失败: 工具不可用: " + toolCall.name();
                    } else {
                        calledSignatures.add(signature);
                        try {
                            result = tool.call(toolCall.arguments(), new ToolContext(toolContext));
                        } catch (Exception e) {
                            result = "执行失败: " + e.getMessage();
                        }
                    }

                    if (isFailure(result)) {
                        consecutiveFailures++;
                    } else {
                        consecutiveFailures = 0;
                    }
                    events.add(PlanActionEvent.actionDone(totalToolCalls, MAX_TOOL_CALLS, toolCall.name(), result));

                    Object loaded = toolContext.remove(SkillToolCallback.CONTEXT_KEY);
                    if (loaded instanceof SkillDefinition skill) {
                        loadedSkill = skill;
                        ToolCallback[] skillTools = toolResolver.resolveTools(skill);
                        activeTools = concat(skillToolCallback, askUserToolCallback, skillTools);
                        activeToolMap = toToolMap(activeTools);
                        pendingContextMessages.add(new SystemMessage(buildLoadedSkillPrompt(skill, result)));
                        events.add(PlanActionEvent.observe(totalToolCalls, "已加载 Skill: " + skill.name() + "，后续工具范围已收敛到 allowedTools。"));
                    }

                    observations.append("- 工具: ").append(toolCall.name()).append("\n")
                            .append("  参数: ").append(toolCall.arguments()).append("\n")
                            .append("  结果: ").append(result).append("\n");
                }

                messages.add(new UserMessage(observations + "\n请基于以上工具结果继续判断下一步：继续调用工具、追问用户，或给出最终回答。"));
                messages.addAll(pendingContextMessages);

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    String msg = loadedSkill == null
                            ? "连续工具调用失败，已停止。请补充更明确的信息后重试。"
                            : "Skill [" + loadedSkill.name() + "] 连续工具调用失败，已停止。请补充更明确的信息后重试。";
                    chatMemory.add(conversationId, new AssistantMessage(msg));
                    events.add(PlanActionEvent.error(msg));
                    events.add(PlanActionEvent.done());
                    return events;
                }
            }

            String msg = "执行轮次过多，已停止以避免循环调用。";
            chatMemory.add(conversationId, new AssistantMessage(msg));
            events.add(PlanActionEvent.error(msg));
            events.add(PlanActionEvent.done());
            return events;
        } finally {
            com.hhx.agi.infra.config.UserContext.clear();
        }
    }

    private ToolCallingChatOptions buildOptions(String model, ToolCallback[] tools, Map<String, Object> toolContext) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .toolContext(toolContext)
                .internalToolExecutionEnabled(false);
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        return builder.build();
    }

    private String normalizeModel(String model) {
        if ("deepseek-v4-flash".equals(model) || "deepseek-v4-pro".equals(model)) {
            return model;
        }
        if (model != null && !model.isBlank()) {
            log.warn("[ToolLoop] 前端传入不支持的模型 [{}]，已切换为 {}", model, DEFAULT_DEEPSEEK_MODEL);
        }
        return DEFAULT_DEEPSEEK_MODEL;
    }

    private String buildSystemPrompt() {
        return """
                你是一个 Claude Code 风格的工具循环 Agent。

                可用 Skills:
                %s

                规则：
                - 当用户任务匹配某个 Skill 时，必须先调用 SkillTool 加载该 Skill，不要直接回答。
                - SkillTool 只负责加载 Skill 上下文；真正业务查询或操作必须继续调用 MCP 工具完成。
                - 如果没有匹配 Skill，但已有 MCP 工具足以完成任务，可以直接调用 MCP 工具。
                - 每次工具返回后，根据结果决定继续调用工具、追问用户或最终回答。
                - 不要编造工具结果中不存在的信息。
                - 如果信息不足，必须调用 AskUser 工具提出一个具体问题，不要把追问作为普通最终回答直接输出。
                """.formatted(skillToolCallback.renderAvailableSkills());
    }

    private String buildLoadedSkillPrompt(SkillDefinition skill, String skillToolResult) {
        return """
                当前已加载 Skill: %s
                Skill 描述: %s
                SkillTool 返回:
                %s

                后续请遵循该 Skill prompt，并优先使用 allowedTools 中的 MCP 工具完成任务。
                如果信息不足，必须调用 AskUser 工具向用户追问一个具体问题。
                """.formatted(skill.name(), skill.description(), skillToolResult);
    }

    private ToolCallback[] safeAllMcpTools() {
        try {
            return toolResolver.getAllTools().values().toArray(new ToolCallback[0]);
        } catch (Exception e) {
            log.warn("[ToolLoop] 获取 MCP 工具失败，仅启用 SkillTool: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }

    private ToolCallback[] concat(ToolCallback first, ToolCallback second, ToolCallback[] rest) {
        ToolCallback[] result = new ToolCallback[(rest == null ? 0 : rest.length) + 2];
        result[0] = first;
        result[1] = second;
        if (rest != null && rest.length > 0) {
            System.arraycopy(rest, 0, result, 2, rest.length);
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

    private String extractAskUserQuestion(String toolInput) {
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
            log.warn("[ToolLoop] AskUser 参数解析失败: {}", e.getMessage());
        }
        return "请补充完成该任务所需的信息。";
    }

    private boolean isLikelyAskUserResponse(String text) {
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

    private static final class AskUserToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                .name(ASK_USER_TOOL_NAME)
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
    }

    private String enrichWithHistory(String conversationId, String userMessage) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.size() <= 1) {
            return userMessage;
        }
        int end = history.size() - 1;
        int start = Math.max(0, end - 16);
        StringBuilder sb = new StringBuilder("## 对话历史（从早到晚）\n");
        for (int i = start; i < end; i++) {
            Message msg = history.get(i);
            String role = msg.getMessageType().name().toLowerCase();
            String text = msg.getText();
            if ("assistant".equals(role) && text != null && text.length() > 200) {
                text = text.substring(0, 200) + "...（已截断）";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        sb.append("\n## 当前用户消息\n").append(userMessage);
        return sb.toString();
    }
}
