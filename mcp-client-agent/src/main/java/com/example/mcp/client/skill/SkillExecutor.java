package com.example.mcp.client.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import com.example.mcp.client.config.McpConnectionManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通用 Skill 执行器 —— 根据 SkillDefinition（从 SKILL.md 加载）执行 Skill
 *
 * 执行逻辑：
 * 1. 将 SKILL.md 正文作为 System Prompt
 * 2. 只绑定该 Skill 声明的 MCP 工具（frontmatter 中的 tools 字段）
 * 3. 让 LLM 按照 SKILL.md 中的 SOP 指令自动执行
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final ChatClient.Builder chatClientBuilder;
    private final McpConnectionManager mcpTools;
    private final SkillLoader skillLoader;
    private final ChatMemory chatMemory;

    @Autowired
    public SkillExecutor(ChatClient.Builder chatClientBuilder, McpConnectionManager mcpTools,
                         SkillLoader skillLoader, ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.mcpTools = mcpTools;
        this.skillLoader = skillLoader;
        this.chatMemory = chatMemory;
    }

    /**
     * 实时获取所有 MCP 工具（延迟加载，避免启动时 MCP 连接未就绪）
     */
    private Map<String, ToolCallback> getAllTools() {
        return Arrays.stream(mcpTools.getToolCallbacks())
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        Function.identity()
                ));
    }

    /**
     * 根据 SKILL.md 中声明的工具名（如 getWeather），从实际注册的 MCP 工具中匹配。
     * 支持精确匹配和后缀匹配（兼容 Spring AI 自动添加的前缀，如 agent_mcp_client_business_server_getWeather）。
     */
    private ToolCallback findTool(Map<String, ToolCallback> allTools, String shortName) {
        // 精确匹配
        if (allTools.containsKey(shortName)) {
            return allTools.get(shortName);
        }
        // 后缀匹配（兼容带前缀的工具名）
        String suffix = "_" + shortName;
        for (Map.Entry<String, ToolCallback> entry : allTools.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 执行指定的 Skill
     */
    public SkillResponse execute(SkillDefinition skill, String conversationId, String userMessage) {
        log.info("[SkillExecutor] 执行 Skill [{}]，用户消息: {}", skill.name(), userMessage);

        // 将用户消息写入 memory
        chatMemory.add(conversationId, new UserMessage(userMessage));

        try {
            // 实时获取工具（避免启动时 MCP 未就绪）
            Map<String, ToolCallback> allTools = getAllTools();
            log.info("[SkillExecutor] 当前可用 MCP 工具: {}", allTools.keySet());

            // 筛选该 Skill 声明的工具（支持后缀匹配，兼容带前缀的工具名）
            List<ToolCallback> matched = new ArrayList<>();
            for (String toolName : skill.allowedTools()) {
                ToolCallback tool = findTool(allTools, toolName);
                if (tool != null) {
                    matched.add(tool);
                } else {
                    log.warn("[SkillExecutor] Skill [{}] 声明的工具 '{}' 未找到", skill.name(), toolName);
                }
            }
            ToolCallback[] skillTools = matched.toArray(new ToolCallback[0]);

            log.info("[SkillExecutor] Skill [{}] 绑定 {} 个工具: {}",
                    skill.name(), skillTools.length,
                    Arrays.stream(skillTools).map(t -> t.getToolDefinition().name()).toList());

            // 按需加载 SKILL.md body（Progressive Disclosure）
            String prompt = skillLoader.loadPrompt(skill);

            // 获取对话历史，拼接到 user 消息中（让 LLM 看到上下文）
            String enrichedUserMessage = enrichWithHistory(conversationId, userMessage);

            // 构建 ChatClient，在 prompt 级别传入 system prompt 和工具（避免污染共享 Builder）
            String content = chatClientBuilder.build()
                    .prompt()
                    .system(prompt)
                    .user(enrichedUserMessage)
                    .toolCallbacks(skillTools)
                    .call()
                    .content();

            // 将助手回复写入 memory
            chatMemory.add(conversationId, new AssistantMessage(content));

            log.info("[SkillExecutor] Skill [{}] 执行完成，响应长度: {}", skill.name(), content.length());
            return new SkillResponse(skill.name(), content);
        } catch (Exception e) {
            log.error("[SkillExecutor] Skill [{}] 执行出错: {}", skill.name(), e.getMessage(), e);
            String errorMsg = "处理请求时出错: " + e.getMessage();
            chatMemory.add(conversationId, new AssistantMessage(errorMsg));
            return new SkillResponse(skill.name(), errorMsg);
        }
    }

    /**
     * Plan-and-Execute 模式（参考 LangGraph Plan-and-Execute）
     *
     * 循环：Plan → Execute Step → Observe → (RePlan?) → Execute Next → ... → Final Answer
     *
     * 每个步骤真正调用工具，Observer 判断结果是否符合预期，
     * 如果不符合则 RePlan 调整剩余步骤，最多 RePlan MAX_REPLAN_ROUNDS 次。
     */
    private static final int MAX_REPLAN_ROUNDS = 3;

    public Flux<PlanActionEvent> planAndExecute(SkillDefinition skill, String conversationId, String userMessage) {
        log.info("[SkillExecutor] Plan&Execute Skill [{}]，用户消息: {}", skill.name(), userMessage);

        // 将用户消息写入 memory（仅外层，内部步骤不写）
        chatMemory.add(conversationId, new UserMessage(userMessage));

        // 共享可变状态（在 boundedElastic 线程中顺序访问，线程安全）
        final List<StepResult> completedSteps = new ArrayList<>();
        final int[] replanCount = {0};
        final int[] stepIndex = {0};
        // steps 用数组包装以便在 lambda 中修改引用
        final List<List<String>> stepsHolder = new ArrayList<>();
        // 缓存 prompt 和 tools，避免每步重复加载（MCP tools/list + SKILL.md IO）
        final String[] cachedPrompt = {null};
        final ToolCallback[][] cachedTools = {null};
        // 标记是否因"追问用户"提前终止（跳过 finalPhase）
        final boolean[] askUserTerminated = {false};
        // 用对话历史丰富用户消息（让 Planner 和 Executor 看到上下文）
        final String enrichedMessage = enrichWithHistory(conversationId, userMessage);

        // Phase 0: 预加载 prompt 和 tools（只加载一次）
        Mono<Void> preload = Mono.fromRunnable(() -> {
            cachedPrompt[0] = skillLoader.loadPrompt(skill);
            cachedTools[0] = resolveTools(skill);
            log.info("[SkillExecutor] 预加载 Skill [{}] prompt({}字) + {} 个工具",
                    skill.name(), cachedPrompt[0].length(), cachedTools[0].length);
        }).subscribeOn(Schedulers.boundedElastic()).then();

        // Phase 1: 生成计划
        Flux<PlanActionEvent> planPhase = Flux.concat(
                emit(PlanActionEvent.planning("正在分析问题并生成执行计划...")),
                Mono.fromCallable(() -> {
                    List<String> steps = generatePlan(skill, enrichedMessage, List.of(), cachedTools[0]);
                    log.info("[Plan] 初始计划: {}", steps);
                    stepsHolder.add(steps);
                    return PlanActionEvent.plan(steps);
                }).subscribeOn(Schedulers.boundedElastic())
        );

        // Phase 2: Execute & Observe 循环（复用缓存的 tools）
        Flux<PlanActionEvent> executePhase = Flux.defer(() -> {
            List<String> steps = stepsHolder.get(stepsHolder.size() - 1);
            stepIndex[0] = 0;
            completedSteps.clear();
            return executeLoop(skill, enrichedMessage, steps, completedSteps, stepIndex, replanCount, stepsHolder,
                    cachedPrompt[0], cachedTools[0], conversationId, askUserTerminated);
        }).subscribeOn(Schedulers.boundedElastic());

        // Phase 3: 最终回复（如果已因"追问用户"提前终止，则跳过）
        Flux<PlanActionEvent> finalPhase = Flux.defer(() -> {
            if (askUserTerminated[0]) {
                log.info("[SkillExecutor] Plan&Execute Skill [{}] 因追问用户提前终止，跳过 finalPhase", skill.name());
                return Flux.just(PlanActionEvent.done());
            }
            return Flux.concat(
                    emit(PlanActionEvent.planning("正在生成最终回复...")),
                    Mono.fromCallable(() -> {
                        String finalAnswer = generateFinalAnswer(skill, enrichedMessage, cachedTools[0], completedSteps,
                                cachedPrompt[0]);
                        // 将最终回复写入 memory（仅外层，内部步骤不写）
                        chatMemory.add(conversationId, new AssistantMessage(finalAnswer));
                        log.info("[SkillExecutor] Plan&Execute Skill [{}] 完成，共 {} 步，RePlan {} 次",
                                skill.name(), completedSteps.size(), replanCount[0]);
                        return PlanActionEvent.result(finalAnswer);
                    }).subscribeOn(Schedulers.boundedElastic()),
                    emit(PlanActionEvent.done())
            );
        });

        return Flux.concat(preload.thenMany(planPhase), executePhase, finalPhase)
                .onErrorResume(e -> {
                    log.error("[SkillExecutor] Plan&Execute Skill [{}] 出错: {}", skill.name(), e.getMessage(), e);
                    return Flux.just(PlanActionEvent.error("执行出错: " + e.getMessage()), PlanActionEvent.done());
                });
    }

    /** 包装单个事件为 Mono（不阻塞） */
    private Mono<PlanActionEvent> emit(PlanActionEvent event) {
        return Mono.just(event);
    }

    /**
     * Execute & Observe 循环 —— 逐步执行，每步产生多个事件
     */
    private static final String ASK_USER_PREFIX = "追问用户";

    private Flux<PlanActionEvent> executeLoop(SkillDefinition skill, String userMessage,
                                               List<String> steps,
                                               List<StepResult> completedSteps, int[] stepIndex,
                                               int[] replanCount, List<List<String>> stepsHolder,
                                               String cachedPrompt, ToolCallback[] cachedTools,
                                               String conversationId, boolean[] askUserTerminated) {
        if (stepIndex[0] >= steps.size()) {
            return Flux.empty();
        }

        String currentStep = steps.get(stepIndex[0]);
        int displayStep = stepIndex[0] + 1;
        int totalSteps = steps.size();

        // 检测"追问用户"步骤 → 直接终止执行流，把问题返回给用户
        if (isAskUserStep(currentStep)) {
            String question = extractAskUserQuestion(currentStep);
            log.info("[SkillExecutor] 检测到追问步骤，终止执行流，返回问题: {}", question);
            askUserTerminated[0] = true;
            // 写入 memory，让下一轮对话有上下文
            chatMemory.add(conversationId, new AssistantMessage(question));
            return Flux.just(
                    PlanActionEvent.result(question),
                    PlanActionEvent.done()
            );
        }

        // 每步产生: actionStart → (阻塞执行) → actionDone → (阻塞观察) → observe → (可能 replan) → 递归下一步
        return Flux.concat(
                // actionStart
                emit(PlanActionEvent.actionStart(displayStep, totalSteps, currentStep)),

                // 执行步骤（阻塞，复用缓存的 tools 和 prompt）
                Mono.fromCallable(() -> {
                    String result = executeStep(skill, userMessage, currentStep, cachedTools, completedSteps,
                            cachedPrompt);
                    log.info("[Action] Step {}: {} → {}", displayStep, currentStep,
                            result.length() > 100 ? result.substring(0, 100) + "..." : result);
                    completedSteps.add(new StepResult(currentStep, result));
                    return PlanActionEvent.actionDone(displayStep, totalSteps, currentStep, result);
                }).subscribeOn(Schedulers.boundedElastic()),

                // 观察（阻塞）+ 可能 replan + 递归下一步
                Flux.defer(() -> {
                    StepResult lastResult = completedSteps.get(completedSteps.size() - 1);
                    String observation = observe(userMessage, currentStep, lastResult.result(), steps, stepIndex[0], completedSteps);
                    log.info("[Observe] Step {}: {}", displayStep, observation);

                    List<PlanActionEvent> events = new ArrayList<>();
                    events.add(PlanActionEvent.observe(displayStep, observation));

                    if (needsReplan(observation) && replanCount[0] < MAX_REPLAN_ROUNDS) {
                        replanCount[0]++;
                        log.info("[RePlan] 第 {} 次重新规划", replanCount[0]);
                        List<String> newSteps = replan(skill, userMessage, completedSteps, observation, cachedTools);
                        log.info("[RePlan] 新计划: {}", newSteps);
                        events.add(PlanActionEvent.replan(observation, newSteps));
                        stepsHolder.add(newSteps);
                        stepIndex[0] = 0;
                        completedSteps.clear();
                        // 递归执行新计划
                        return Flux.concat(Flux.fromIterable(events),
                                executeLoop(skill, userMessage, newSteps, completedSteps, stepIndex, replanCount, stepsHolder,
                                        cachedPrompt, cachedTools, conversationId, askUserTerminated));
                    } else {
                        stepIndex[0]++;
                        // 递归执行下一步
                        return Flux.concat(Flux.fromIterable(events),
                                executeLoop(skill, userMessage, steps, completedSteps, stepIndex, replanCount, stepsHolder,
                                        cachedPrompt, cachedTools, conversationId, askUserTerminated));
                    }
                }).subscribeOn(Schedulers.boundedElastic())
        );
    }

    /** 判断步骤是否为"追问用户" */
    private boolean isAskUserStep(String step) {
        return step.startsWith(ASK_USER_PREFIX) || step.startsWith("等待用户");
    }

    /** 从"追问用户：xxx"中提取问题文本 */
    private String extractAskUserQuestion(String step) {
        // 支持 "追问用户：xxx" / "追问用户: xxx" / "追问用户，xxx" 等格式
        for (String sep : new String[]{"：", ":", "，", ","}) {
            int idx = step.indexOf(sep);
            if (idx > 0 && idx < step.length() - 1) {
                String question = step.substring(idx + sep.length()).trim();
                if (!question.isEmpty()) return question;
            }
        }
        // fallback: Planner 没写具体问题，生成默认追问
        return "请问您能提供更多信息吗？例如城市名称、订单号等。";
    }

    /** 步骤执行结果 */
    private record StepResult(String step, String result) {}

    /**
     * Planner —— 生成执行计划
     */
    private List<String> generatePlan(SkillDefinition skill, String userMessage,
                                      List<StepResult> completed, ToolCallback[] tools) {
        String completedInfo = completed.isEmpty() ? "无" :
                completed.stream().map(s -> "- " + s.step() + " → " + s.result()).collect(Collectors.joining("\n"));

        String toolsInfo = formatToolSignatures(tools);

        String prompt = """
                你是一个任务规划器。根据用户问题、可用工具和已完成步骤，生成接下来的执行计划。
                
                规则：
                - 每行一个步骤，不要编号，不要多余内容
                - 步骤要具体、可执行，明确说明要调用哪个工具和参数
                - 如果不需要工具，直接写"回复用户"
                - 最后一步应该是"整理结果并回复用户"
                - **重要**：仔细检查每个工具的必填参数（required），如果用户消息中缺少必填参数的值，第一步应该是"追问用户"而不是用占位符调用工具
                - **追问格式**：追问步骤必须写成"追问用户：具体问题内容"，例如"追问用户：请问您想查询哪个城市的天气？"，不要只写"追问用户"
                - **禁止反复追问**：只有在缺少必填参数（如城市名、订单号）时才追问。如果用户意图和参数都明确但工具能力有限（如用户问"明天天气"但工具只能查实时天气），应直接用现有工具执行，并在回复中说明限制，绝不要追问
                - **能力不足时**：如果工具无法完全满足用户需求，先尽力用现有工具获取最接近的结果，再在回复中补充说明
                
                可用工具（含参数签名）:
                %s
                用户问题: %s
                已完成步骤:
                %s
                
                请输出接下来要执行的步骤（每行一个）：
                """.formatted(
                toolsInfo,
                userMessage,
                completedInfo
        );

        String result = chatClientBuilder.build().prompt().user(prompt).call().content();
        return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.startsWith("#"))
                .toList();
    }

    /**
     * Executor —— 执行单个步骤（带工具调用）
     */
    private String executeStep(SkillDefinition skill, String userMessage, String stepDesc,
                               ToolCallback[] skillTools, List<StepResult> completedSteps,
                               String skillPrompt) {
        String historyInfo = completedSteps.isEmpty() ? "" :
                completedSteps.stream()
                        .map(s -> "步骤「" + s.step() + "」结果: " + s.result())
                        .collect(Collectors.joining("\n"));
        String stepPrompt = """
                %s
                
                你正在执行以下步骤: %s
                
                用户原始问题: %s
                %s
                
                请执行这个步骤。如果需要调用工具，请调用。只返回这一步的执行结果，不要返回最终回复。
                """.formatted(
                skillPrompt,
                stepDesc,
                userMessage,
                historyInfo.isEmpty() ? "" : "之前步骤的结果:\n" + historyInfo
        );

        try {
            String result = chatClientBuilder.build()
                    .prompt()
                    .user(stepPrompt)
                    .toolCallbacks(skillTools)
                    .call()
                    .content();
            return result != null ? result : "(无结果)";
        } catch (Exception e) {
            log.warn("[Executor] 步骤执行失败: {} - {}", stepDesc, e.getMessage());
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * Observer —— 观察步骤结果，判断是否需要 RePlan
     */
    private String observe(String userMessage, String stepDesc, String stepResult,
                           List<String> remainingSteps, int currentIndex, List<StepResult> completed) {
        String prompt = """
                你是一个任务观察者。请评估当前步骤的执行结果。
                
                用户问题: %s
                当前步骤: %s
                执行结果: %s
                剩余步骤: %s
                
                请用一句话评估：
                - 如果结果正常且剩余步骤合理，回复 "OK: [简要说明]"
                - 如果结果异常或需要调整计划，回复 "REPLAN: [原因和建议]"
                """.formatted(
                userMessage,
                stepDesc,
                stepResult.length() > 500 ? stepResult.substring(0, 500) + "..." : stepResult,
                currentIndex + 1 < remainingSteps.size()
                        ? String.join(", ", remainingSteps.subList(currentIndex + 1, remainingSteps.size()))
                        : "无"
        );

        try {
            return chatClientBuilder.build().prompt().user(prompt).call().content().trim();
        } catch (Exception e) {
            return "OK: 观察异常，继续执行";
        }
    }

    /** 判断观察结果是否需要 RePlan */
    private boolean needsReplan(String observation) {
        return observation != null && observation.toUpperCase().startsWith("REPLAN");
    }

    /**
     * RePlan —— 根据已完成步骤和观察结果，重新生成计划
     */
    private List<String> replan(SkillDefinition skill, String userMessage,
                                List<StepResult> completed, String observation,
                                ToolCallback[] tools) {
        return generatePlan(skill, userMessage, completed, tools);
    }

    /**
     * Final Answer —— 基于所有步骤结果生成最终回复
     */
    private String generateFinalAnswer(SkillDefinition skill, String userMessage,
                                       ToolCallback[] skillTools, List<StepResult> completedSteps,
                                       String skillPrompt) {
        String stepsInfo = completedSteps.stream()
                .map(s -> "步骤「" + s.step() + "」结果:\n" + s.result())
                .collect(Collectors.joining("\n\n"));
        String prompt = """
                %s
                
                用户问题: %s
                
                以下是已执行步骤及其结果：
                %s
                
                请根据以上所有步骤的结果，生成对用户问题的最终完整回复。
                """.formatted(skillPrompt, userMessage, stepsInfo);

        return chatClientBuilder.build().prompt().system(skillPrompt).user(prompt).call().content();
    }

    /**
     * 格式化工具签名（含参数名、类型、required），供 Planner 判断参数是否齐全
     */
    private String formatToolSignatures(ToolCallback[] tools) {
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
     * 将对话历史拼接到用户消息中，让 LLM 看到多轮上下文
     * 只取最近几轮（避免 token 过多），不包含当前这条（已单独传入）
     */
    private String enrichWithHistory(String conversationId, String userMessage) {
        List<Message> history = chatMemory.get(conversationId);
        // history 中最后一条是刚写入的当前 userMessage，排除它
        if (history == null || history.size() <= 1) {
            return userMessage;
        }
        // 取最近 16 条历史（不含当前消息），覆盖更多轮对话
        int end = history.size() - 1;
        int start = Math.max(0, end - 16);
        StringBuilder sb = new StringBuilder("## 对话历史（从早到晚）\n");
        for (int i = start; i < end; i++) {
            Message msg = history.get(i);
            String role = msg.getMessageType().name().toLowerCase();
            String text = msg.getText();
            // 截断过长的助手回复（保留摘要，避免 token 浪费）
            if ("assistant".equals(role) && text != null && text.length() > 200) {
                text = text.substring(0, 200) + "...（已截断）";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        sb.append("\n## 当前用户消息\n").append(userMessage);
        return sb.toString();
    }

    /**
     * 解析 Skill 声明的工具
     */
    private ToolCallback[] resolveTools(SkillDefinition skill) {
        Map<String, ToolCallback> allTools = getAllTools();
        List<ToolCallback> matched = new ArrayList<>();
        for (String toolName : skill.allowedTools()) {
            ToolCallback tool = findTool(allTools, toolName);
            if (tool != null) {
                matched.add(tool);
            } else {
                log.warn("[SkillExecutor] Skill [{}] 声明的工具 '{}' 未找到", skill.name(), toolName);
            }
        }
        return matched.toArray(new ToolCallback[0]);
    }
}
