package com.hhx.agi.application.agent.execution;

import com.hhx.agi.application.agent.model.AgentStreamEvent;
import com.hhx.agi.application.agent.model.SkillDefinition;
import com.hhx.agi.application.agent.tool.AskUserToolCallback;
import com.hhx.agi.application.agent.tool.SkillToolCallback;
import com.hhx.agi.application.agent.tool.ToolResolver;
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
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
public class AgenticLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgenticLoopExecutor.class);

    private static final int MAX_AGENTIC_LOOP_ROUNDS = 8;
    private static final int MAX_TOOL_CALLS = 20;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ToolResolver toolResolver;
    private final SkillToolCallback skillToolCallback;
    private final AskUserToolCallback askUserToolCallback;

    public AgenticLoopExecutor(ChatModel chatModel, ChatMemory chatMemory, ToolResolver toolResolver,
                               SkillToolCallback skillToolCallback, AskUserToolCallback askUserToolCallback) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.toolResolver = toolResolver;
        this.skillToolCallback = skillToolCallback;
        this.askUserToolCallback = askUserToolCallback;
    }

    public Flux<AgentStreamEvent> execute(String conversationId, String userMessage, String model, String userId) {
        return Flux.create(sink -> {
            Disposable disposable = Mono.fromRunnable(() -> {
                try {
                    executeBlocking(conversationId, userMessage, model, userId, sink);
                } catch (Exception e) {
                    if (!sink.isCancelled()) {
                        log.error("[AgenticLoop] 执行失败: {}", e.getMessage(), e);
                        sink.next(AgentStreamEvent.error("执行出错: " + e.getMessage()));
                        sink.next(AgentStreamEvent.done());
                    }
                } finally {
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                }
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            sink.onCancel(disposable::dispose);
        });
    }

    private List<AgentStreamEvent> executeBlocking(String conversationId, String userMessage, String model, String userId,
                                                   FluxSink<AgentStreamEvent> eventSink) {
        com.hhx.agi.infra.config.UserContext.setUserId(userId);
        try {
            List<AgentStreamEvent> events = new StreamingEventList(eventSink);
            List<Message> messages = new ArrayList<>();
            chatMemory.add(conversationId, new UserMessage(userMessage));

            messages.add(new SystemMessage(buildSystemPrompt()));
            messages.add(new UserMessage(enrichWithHistory(conversationId, userMessage)));
            events.add(AgentStreamEvent.thinking("🤔 正在分析可用 Skill 与 MCP 工具..."));

            ToolCallback[] allMcpTools = safeAllMcpTools();
            ToolCallback[] activeTools = concat(skillToolCallback, askUserToolCallback, allMcpTools);
            Map<String, ToolCallback> activeToolMap = toToolMap(activeTools);
            Map<String, Object> toolContext = new LinkedHashMap<>();
            Set<String> calledSignatures = new LinkedHashSet<>();

            int totalToolCalls = 0;
            int consecutiveFailures = 0;
            SkillDefinition loadedSkill = null;

            for (int round = 1; round <= MAX_AGENTIC_LOOP_ROUNDS; round++) {
                ChatResponse response = chatModel.call(new Prompt(messages, buildOptions(normalizeModel(model), activeTools, toolContext)));
                AssistantMessage assistant = response.getResult().getOutput();

                if (!assistant.hasToolCalls()) {
                    messages.add(assistant);
                    String finalAnswer = assistant.getText();
                    if (finalAnswer == null || finalAnswer.isBlank()) {
                        finalAnswer = "已完成处理，但模型未返回有效文本。";
                    }
                    chatMemory.add(conversationId, new AssistantMessage(finalAnswer));
                    if (askUserToolCallback.isLikelyAskUserResponse(finalAnswer)) {
                        events.add(AgentStreamEvent.askUser(finalAnswer));
                    }
                    events.add(AgentStreamEvent.finalAnswer(finalAnswer));
                    events.add(AgentStreamEvent.done());
                    return events;
                }

                StringBuilder observations = new StringBuilder("## 工具执行结果\n");
                List<Message> pendingContextMessages = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    totalToolCalls++;
                    if (totalToolCalls > MAX_TOOL_CALLS) {
                        String msg = "工具调用次数过多，已停止以避免循环调用。";
                        chatMemory.add(conversationId, new AssistantMessage(msg));
                        events.add(AgentStreamEvent.error(msg));
                        events.add(AgentStreamEvent.done());
                        return events;
                    }

                    ToolCallback tool = activeToolMap.get(toolCall.name());
                    String signature = toolCall.name() + ":" + toolCall.arguments();
                    String result;
                    events.add(AgentStreamEvent.toolCallStart(totalToolCalls, MAX_TOOL_CALLS, toolCall.name()));

                    if (askUserToolCallback.isAskUserTool(toolCall.name())) {
                        result = askUserToolCallback.extractQuestion(toolCall.arguments());
                        events.add(AgentStreamEvent.toolCallDone(totalToolCalls, MAX_TOOL_CALLS, toolCall.name(), result));
                        chatMemory.add(conversationId, new AssistantMessage(result));
                        events.add(AgentStreamEvent.askUser(result));
                        events.add(AgentStreamEvent.finalAnswer(result));
                        events.add(AgentStreamEvent.done());
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
                    events.add(AgentStreamEvent.toolCallDone(totalToolCalls, MAX_TOOL_CALLS, toolCall.name(), result));

                    Object loaded = toolContext.remove(SkillToolCallback.CONTEXT_KEY);
                    if (loaded instanceof SkillDefinition skill) {
                        loadedSkill = skill;
                        ToolCallback[] skillTools = toolResolver.resolveTools(skill);
                        activeTools = concat(skillToolCallback, askUserToolCallback, skillTools);
                        activeToolMap = toToolMap(activeTools);
                        pendingContextMessages.add(new SystemMessage(buildLoadedSkillPrompt(skill, result)));
                        events.add(AgentStreamEvent.observation(totalToolCalls, "已加载 Skill: " + skill.name() + "，后续工具范围已收敛到 allowedTools。"));
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
                    events.add(AgentStreamEvent.error(msg));
                    events.add(AgentStreamEvent.done());
                    return events;
                }
            }

            String msg = "执行轮次过多，已停止以避免循环调用。";
            chatMemory.add(conversationId, new AssistantMessage(msg));
            events.add(AgentStreamEvent.error(msg));
            events.add(AgentStreamEvent.done());
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
            log.warn("[AgenticLoop] 前端传入不支持的模型 [{}]，已切换为 {}", model, DEFAULT_DEEPSEEK_MODEL);
        }
        return DEFAULT_DEEPSEEK_MODEL;
    }

    private String buildSystemPrompt() {
        return """
                你是一个 Claude Code 风格的 Agentic Loop Agent。

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
            log.warn("[AgenticLoop] 获取 MCP 工具失败，仅启用 SkillTool: {}", e.getMessage());
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

    private static final class StreamingEventList extends ArrayList<AgentStreamEvent> {

        private final FluxSink<AgentStreamEvent> sink;

        private StreamingEventList(FluxSink<AgentStreamEvent> sink) {
            this.sink = sink;
        }

        @Override
        public boolean add(AgentStreamEvent event) {
            if (event != null && !sink.isCancelled()) {
                sink.next(event);
            }
            return super.add(event);
        }
    }
}
