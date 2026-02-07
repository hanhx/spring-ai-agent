package com.example.mcp.client.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
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

    @Autowired
    public SkillExecutor(ChatClient.Builder chatClientBuilder, McpConnectionManager mcpTools) {
        this.chatClientBuilder = chatClientBuilder;
        this.mcpTools = mcpTools;
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
    public SkillResponse execute(SkillDefinition skill, String userMessage) {
        log.info("[SkillExecutor] 执行 Skill [{}]，用户消息: {}", skill.name(), userMessage);

        try {
            // 实时获取工具（避免启动时 MCP 未就绪）
            Map<String, ToolCallback> allTools = getAllTools();
            log.info("[SkillExecutor] 当前可用 MCP 工具: {}", allTools.keySet());

            // 筛选该 Skill 声明的工具（支持后缀匹配，兼容带前缀的工具名）
            List<ToolCallback> matched = new ArrayList<>();
            for (String toolName : skill.tools()) {
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

            // 构建 ChatClient，在 prompt 级别传入 system prompt 和工具（避免污染共享 Builder）
            String content = chatClientBuilder.build()
                    .prompt()
                    .system(skill.prompt())
                    .user(userMessage)
                    .toolCallbacks(skillTools)
                    .call()
                    .content();

            log.info("[SkillExecutor] Skill [{}] 执行完成，响应长度: {}", skill.name(), content.length());
            return new SkillResponse(skill.name(), content);
        } catch (Exception e) {
            log.error("[SkillExecutor] Skill [{}] 执行出错: {}", skill.name(), e.getMessage(), e);
            return new SkillResponse(skill.name(), "处理请求时出错: " + e.getMessage());
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

    public Flux<PlanActionEvent> planAndExecute(SkillDefinition skill, String userMessage) {
        log.info("[SkillExecutor] Plan&Execute Skill [{}]，用户消息: {}", skill.name(), userMessage);

        // 共享可变状态（在 boundedElastic 线程中顺序访问，线程安全）
        final List<StepResult> completedSteps = new ArrayList<>();
        final int[] replanCount = {0};
        final int[] stepIndex = {0};
        // steps 用数组包装以便在 lambda 中修改引用
        final List<List<String>> stepsHolder = new ArrayList<>();

        // Phase 1: 生成计划
        Flux<PlanActionEvent> planPhase = Flux.concat(
                emit(PlanActionEvent.planning("正在分析问题并生成执行计划...")),
                Mono.fromCallable(() -> {
                    List<String> steps = generatePlan(skill, userMessage, List.of());
                    log.info("[Plan] 初始计划: {}", steps);
                    stepsHolder.add(steps);
                    return PlanActionEvent.plan(steps);
                }).subscribeOn(Schedulers.boundedElastic())
        );

        // Phase 2: Execute & Observe 循环（工具在每步内实时解析，避免绑定死连接）
        Flux<PlanActionEvent> executePhase = Flux.defer(() -> {
            List<String> steps = stepsHolder.get(stepsHolder.size() - 1);
            stepIndex[0] = 0;
            completedSteps.clear();
            return executeLoop(skill, userMessage, steps, completedSteps, stepIndex, replanCount, stepsHolder);
        }).subscribeOn(Schedulers.boundedElastic());

        // Phase 3: 最终回复
        Flux<PlanActionEvent> finalPhase = Flux.concat(
                emit(PlanActionEvent.planning("正在生成最终回复...")),
                Mono.fromCallable(() -> {
                    ToolCallback[] freshTools = resolveTools(skill);
                    String finalAnswer = generateFinalAnswer(skill, userMessage, freshTools, completedSteps);
                    log.info("[SkillExecutor] Plan&Execute Skill [{}] 完成，共 {} 步，RePlan {} 次",
                            skill.name(), completedSteps.size(), replanCount[0]);
                    return PlanActionEvent.result(finalAnswer);
                }).subscribeOn(Schedulers.boundedElastic()),
                emit(PlanActionEvent.done())
        );

        return Flux.concat(planPhase, executePhase, finalPhase)
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
    private Flux<PlanActionEvent> executeLoop(SkillDefinition skill, String userMessage,
                                               List<String> steps,
                                               List<StepResult> completedSteps, int[] stepIndex,
                                               int[] replanCount, List<List<String>> stepsHolder) {
        if (stepIndex[0] >= steps.size()) {
            return Flux.empty();
        }

        String currentStep = steps.get(stepIndex[0]);
        int displayStep = stepIndex[0] + 1;
        int totalSteps = steps.size();

        // 每步产生: actionStart → (阻塞执行) → actionDone → (阻塞观察) → observe → (可能 replan) → 递归下一步
        return Flux.concat(
                // actionStart
                emit(PlanActionEvent.actionStart(displayStep, totalSteps, currentStep)),

                // 执行步骤（阻塞，每步实时解析工具避免绑定死连接）
                Mono.fromCallable(() -> {
                    ToolCallback[] freshTools = resolveTools(skill);
                    String result = executeStep(skill, userMessage, currentStep, freshTools, completedSteps);
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
                        List<String> newSteps = replan(skill, userMessage, completedSteps, observation);
                        log.info("[RePlan] 新计划: {}", newSteps);
                        events.add(PlanActionEvent.replan(observation, newSteps));
                        stepsHolder.add(newSteps);
                        stepIndex[0] = 0;
                        completedSteps.clear();
                        // 递归执行新计划
                        return Flux.concat(Flux.fromIterable(events),
                                executeLoop(skill, userMessage, newSteps, completedSteps, stepIndex, replanCount, stepsHolder));
                    } else {
                        stepIndex[0]++;
                        // 递归执行下一步
                        return Flux.concat(Flux.fromIterable(events),
                                executeLoop(skill, userMessage, steps, completedSteps, stepIndex, replanCount, stepsHolder));
                    }
                }).subscribeOn(Schedulers.boundedElastic())
        );
    }

    /** 步骤执行结果 */
    private record StepResult(String step, String result) {}

    /**
     * Planner —— 生成执行计划
     */
    private List<String> generatePlan(SkillDefinition skill, String userMessage, List<StepResult> completed) {
        String completedInfo = completed.isEmpty() ? "无" :
                completed.stream().map(s -> "- " + s.step() + " → " + s.result()).collect(Collectors.joining("\n"));

        String prompt = """
                你是一个任务规划器。根据用户问题、可用工具和已完成步骤，生成接下来的执行计划。
                
                规则：
                - 每行一个步骤，不要编号，不要多余内容
                - 步骤要具体、可执行，明确说明要调用哪个工具和参数
                - 如果不需要工具，直接写"回复用户"
                - 最后一步应该是"整理结果并回复用户"
                
                可用工具: %s
                用户问题: %s
                已完成步骤:
                %s
                
                请输出接下来要执行的步骤（每行一个）：
                """.formatted(
                skill.tools().isEmpty() ? "无" : String.join(", ", skill.tools()),
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
                               ToolCallback[] skillTools, List<StepResult> completedSteps) {
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
                skill.prompt(),
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
                                List<StepResult> completed, String observation) {
        return generatePlan(skill, userMessage, completed);
    }

    /**
     * Final Answer —— 基于所有步骤结果生成最终回复
     */
    private String generateFinalAnswer(SkillDefinition skill, String userMessage,
                                       ToolCallback[] skillTools, List<StepResult> completedSteps) {
        String stepsInfo = completedSteps.stream()
                .map(s -> "步骤「" + s.step() + "」结果:\n" + s.result())
                .collect(Collectors.joining("\n\n"));

        String prompt = """
                %s
                
                用户问题: %s
                
                以下是已执行步骤及其结果：
                %s
                
                请根据以上所有步骤的结果，生成对用户问题的最终完整回复。
                """.formatted(skill.prompt(), userMessage, stepsInfo);

        return chatClientBuilder.build().prompt().system(skill.prompt()).user(prompt).call().content();
    }

    /**
     * 解析 Skill 声明的工具
     */
    private ToolCallback[] resolveTools(SkillDefinition skill) {
        Map<String, ToolCallback> allTools = getAllTools();
        List<ToolCallback> matched = new ArrayList<>();
        for (String toolName : skill.tools()) {
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
