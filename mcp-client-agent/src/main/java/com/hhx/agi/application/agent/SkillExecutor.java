package com.hhx.agi.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Skill 执行器（Facade） —— 编排 Plan-and-Execute 流程
 *
 * 职责拆分：
 *   - ToolResolver: 工具解析与匹配
 *   - ExecutionContext: 执行状态管理
 *   - SkillExecutor: 流程编排（本类）
 *
 * 内部角色（LLM 调用）：
 *   - Planner: 生成/重新生成执行计划
 *   - StepExecutor: 执行单个步骤（带工具调用）
 *   - Observer: 观察步骤结果，判断是否需要 RePlan
 *   - Summarizer: 基于所有步骤结果生成最终回复
 */
@Component
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private static final int MAX_REPLAN_ROUNDS = 3;
    private static final int MAX_ASK_USER_ROUNDS = 4;
    private static final int MAX_TOTAL_EXECUTED_STEPS = 20;
    private static final int MAX_SAME_STEP_EXECUTIONS = 2;
    private static final String ASK_USER_PREFIX = "追问用户";

    private final ChatClient.Builder chatClientBuilder;
    private final ToolResolver toolResolver;
    private final SkillLoader skillLoader;
    private final ChatMemory chatMemory;

    @Autowired
    public SkillExecutor(ChatClient.Builder chatClientBuilder, ToolResolver toolResolver,
                         SkillLoader skillLoader, ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolResolver = toolResolver;
        this.skillLoader = skillLoader;
        this.chatMemory = chatMemory;
    }

    // ==================== 公开 API ====================

    /**
     * 简单执行模式 —— 直接调用 LLM + 工具，不走 Plan-and-Execute
     */
    public SkillResponse execute(SkillDefinition skill, String conversationId, String userMessage) {
        log.info("[SkillExecutor] 执行 Skill [{}]，用户消息: {}", skill.name(), userMessage);
        chatMemory.add(conversationId, new UserMessage(userMessage));

        try {
            ToolCallback[] skillTools = toolResolver.resolveTools(skill);
            String prompt = skillLoader.loadPrompt(skill);
            String enrichedUserMessage = enrichWithHistory(conversationId, userMessage);

            String content = chatClientBuilder.build()
                    .prompt()
                    .system(prompt)
                    .user(enrichedUserMessage)
                    .toolCallbacks(skillTools)
                    .call()
                    .content();

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

    private DirectToolStep parseDirectToolStep(String stepDesc, ToolCallback[] cachedTools) {
        if (stepDesc == null || stepDesc.isBlank() || cachedTools == null || cachedTools.length == 0) {
            return null;
        }
        int colonIdx = stepDesc.indexOf(':');
        if (colonIdx <= 0 || colonIdx >= stepDesc.length() - 1) {
            return null;
        }

        String toolMarker = stepDesc.substring(0, colonIdx).trim();
        String toolInput = stepDesc.substring(colonIdx + 1).trim();
        if (!toolInput.startsWith("{")) {
            return null;
        }

        ToolCallback[] matched = selectToolsForStep(toolMarker, cachedTools);
        if (matched == null || matched.length != 1) {
            return null;
        }

        return new DirectToolStep(matched[0], toolInput);
    }

    /**
     * Plan-and-Execute 流式模式 —— Plan → Execute → Observe → (RePlan?) → Final Answer
     */
    public Flux<PlanActionEvent> planAndExecute(SkillDefinition skill, String conversationId, String userMessage, String model) {
        log.info("[SkillExecutor] Plan&Execute Skill [{}]，用户消息: {}，model: {}", skill.name(), userMessage, model);
        chatMemory.add(conversationId, new UserMessage(userMessage));

        String enrichedMessage = enrichWithHistory(conversationId, userMessage);
        ExecutionContext ctx = new ExecutionContext(skill, conversationId, userMessage, enrichedMessage, model);

        Mono<Void> preload = preloadPhase(ctx);
        Flux<PlanActionEvent> planPhase = planPhase(ctx);
        Flux<PlanActionEvent> executePhase = executePhase(ctx);
        Flux<PlanActionEvent> finalPhase = finalPhase(ctx);

        return Flux.concat(preload.thenMany(planPhase), executePhase, finalPhase)
                .onErrorResume(e -> {
                    log.error("[SkillExecutor] Plan&Execute Skill [{}] 出错: {}", skill.name(), e.getMessage(), e);
                    return Flux.just(PlanActionEvent.error("执行出错: " + e.getMessage()), PlanActionEvent.done());
                });
    }

    // ==================== Phase 编排 ====================

    /** Phase 0: 预加载 prompt 和 tools（只加载一次） */
    private Mono<Void> preloadPhase(ExecutionContext ctx) {
        return Mono.fromRunnable(() -> {
            ctx.setCachedPrompt(skillLoader.loadPrompt(ctx.skill()));
            ctx.setCachedTools(toolResolver.resolveTools(ctx.skill()));
            log.info("[SkillExecutor] 预加载 Skill [{}] prompt({}字) + {} 个工具",
                    ctx.skill().name(), ctx.cachedPrompt().length(), ctx.cachedTools().length);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** Phase 1: 生成初始计划 */
    private Flux<PlanActionEvent> planPhase(ExecutionContext ctx) {
        return Flux.concat(
                Mono.just(PlanActionEvent.planning("正在分析问题并生成执行计划...")),
                Mono.fromCallable(() -> {
                    List<String> steps = invokePlanner(ctx, List.of());
                    log.info("[Plan] 初始计划: {}", steps);
                    ctx.addPlan(steps);
                    return PlanActionEvent.plan(steps);
                }).subscribeOn(Schedulers.boundedElastic())
        );
    }

    /** Phase 2: Execute & Observe 循环 */
    private Flux<PlanActionEvent> executePhase(ExecutionContext ctx) {
        return Flux.defer(() -> executeLoop(ctx)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Phase 3: 最终回复 */
    private Flux<PlanActionEvent> finalPhase(ExecutionContext ctx) {
        return Flux.defer(() -> {
            if (ctx.isAskUserTerminated()) {
                log.info("[SkillExecutor] Plan&Execute Skill [{}] 因追问用户提前终止，跳过 finalPhase", ctx.skill().name());
                return Flux.just(PlanActionEvent.done());
            }
            return Flux.concat(
                    Mono.just(PlanActionEvent.planning("正在生成最终回复...")),
                    Mono.fromCallable(() -> {
                        String finalAnswer = invokeSummarizer(ctx);
                        chatMemory.add(ctx.conversationId(), new AssistantMessage(finalAnswer));
                        log.info("[SkillExecutor] Plan&Execute Skill [{}] 完成，共 {} 步，RePlan {} 次",
                                ctx.skill().name(), ctx.completedSteps().size(), ctx.replanCount());
                        return PlanActionEvent.result(finalAnswer);
                    }).subscribeOn(Schedulers.boundedElastic()),
                    Mono.just(PlanActionEvent.done())
            );
        });
    }

    // ==================== Execute Loop ====================

    private Flux<PlanActionEvent> executeLoop(ExecutionContext ctx) {
        if (!ctx.hasMoreSteps()) {
            return Flux.empty();
        }
        if (ctx.totalExecutedSteps() >= MAX_TOTAL_EXECUTED_STEPS) {
            String msg = "执行步骤过多，已自动停止以避免循环调用。请重试或调整问题范围。";
            log.warn("[SkillExecutor] 命中执行上限({})，conversationId={}", MAX_TOTAL_EXECUTED_STEPS, ctx.conversationId());
            chatMemory.add(ctx.conversationId(), new AssistantMessage(msg));
            return Flux.just(PlanActionEvent.error(msg), PlanActionEvent.done());
        }

        String currentStep = ctx.currentStep();
        int displayStep = ctx.currentStepDisplay();
        int totalSteps = ctx.totalSteps();

        int sameStepExecuted = countStepExecutions(ctx, currentStep);
        if (sameStepExecuted >= MAX_SAME_STEP_EXECUTIONS) {
            String msg = "检测到同一步骤重复执行，已中止以避免重复调用下游服务。";
            log.warn("[SkillExecutor] 同一步骤重复执行超限({})，step='{}'，conversationId={}",
                    MAX_SAME_STEP_EXECUTIONS, currentStep, ctx.conversationId());
            chatMemory.add(ctx.conversationId(), new AssistantMessage(msg));
            return Flux.just(PlanActionEvent.error(msg), PlanActionEvent.done());
        }

        // 追问检测 + 上限防御
        if (isAskUserStep(currentStep)) {
            return handleAskUser(ctx, currentStep);
        }

        return Flux.concat(
                // actionStart
                Mono.just(PlanActionEvent.actionStart(displayStep, totalSteps, currentStep)),

                // 执行步骤
                Mono.fromCallable(() -> {
                    String result = invokeStepExecutor(ctx, currentStep);
                    log.info("[Action] Step {}: {} → {}", displayStep, currentStep,
                            result.length() > 100 ? result.substring(0, 100) + "..." : result);
                    ctx.addCompletedStep(currentStep, result);
                    ctx.incrementExecutedSteps();
                    return PlanActionEvent.actionDone(displayStep, totalSteps, currentStep, result);
                }).subscribeOn(Schedulers.boundedElastic()),

                // 观察 + 可能 replan + 递归
                Flux.defer(() -> {
                    var lastResult = ctx.completedSteps().get(ctx.completedSteps().size() - 1);
                    String observation = invokeObserver(ctx, currentStep, lastResult.result());
                    log.info("[Observe] Step {}: {}", displayStep, observation);

                    List<PlanActionEvent> events = new ArrayList<>();
                    events.add(PlanActionEvent.observe(displayStep, observation));

                    if (shouldReplan(observation, lastResult.result()) && ctx.replanCount() < MAX_REPLAN_ROUNDS) {
                        ctx.incrementReplan();
                        log.info("[RePlan] 第 {} 次重新规划", ctx.replanCount());
                        List<String> newSteps = invokePlanner(ctx, ctx.completedSteps());
                        log.info("[RePlan] 新计划: {}", newSteps);
                        events.add(PlanActionEvent.replan(observation, newSteps));
                        ctx.resetForReplan(newSteps);
                        return Flux.concat(Flux.fromIterable(events), executeLoop(ctx));
                    } else {
                        ctx.advanceStep();
                        return Flux.concat(Flux.fromIterable(events), executeLoop(ctx));
                    }
                }).subscribeOn(Schedulers.boundedElastic())
        );
    }

    /** 处理追问步骤（含上限防御） */
    private Flux<PlanActionEvent> handleAskUser(ExecutionContext ctx, String currentStep) {
        int count = ctx.incrementAskUser();
        if (count > MAX_ASK_USER_ROUNDS) {
            log.warn("[SkillExecutor] 追问次数已达上限({})，不再追问，用已有信息兜底回复", MAX_ASK_USER_ROUNDS);
            String fallback = "抱歉，我无法获取足够的信息来完成您的请求。请您提供更完整的信息后再试。";
            ctx.terminateWithAskUser();
            chatMemory.add(ctx.conversationId(), new AssistantMessage(fallback));
            return Flux.just(PlanActionEvent.result(fallback), PlanActionEvent.done());
        }
        String question = extractAskUserQuestion(currentStep);
        log.info("[SkillExecutor] 检测到追问步骤(第{}次)，终止执行流，返回问题: {}", count, question);
        ctx.terminateWithAskUser();
        chatMemory.add(ctx.conversationId(), new AssistantMessage(question));
        return Flux.just(PlanActionEvent.result(question), PlanActionEvent.done());
    }

    // ==================== LLM 角色调用 ====================

    /** Planner —— 生成执行计划 */
    private List<String> invokePlanner(ExecutionContext ctx, List<ExecutionContext.StepResult> completed) {
        String completedInfo = completed.isEmpty() ? "无" :
                completed.stream().map(s -> "- " + s.step() + " → " + s.result()).collect(Collectors.joining("\n"));
        String toolsInfo = toolResolver.formatToolSignatures(ctx.cachedTools());

        String prompt = """
                你是一个任务规划器。根据用户问题、可用工具和已完成步骤，生成「机器可执行」计划。

                严格输出规则：
                1) 每行一个步骤，不要编号，不要解释，不要 markdown
                2) 工具步骤必须是：<工具名>: <JSON参数>
                   例如：JavaSDKMCPClient_getWeatherForecast: {"city":"上海","dayOffset":1}
                3) 若缺少必填参数，必须输出：追问用户：<具体问题>
                4) 最后一步必须是：整理结果并回复用户
                5) 禁止输出“我认为/建议/说明”这类描述性文字
                6) 不能编造不存在的工具名

                可用工具（含参数签名）:
                %s
                用户问题: %s
                已完成步骤:
                %s

                请直接输出步骤：
                """.formatted(toolsInfo, ctx.enrichedMessage(), completedInfo);

        ChatClient.Builder builder = chatClientBuilder.clone();
        if (ctx.model() != null && !ctx.model().isBlank()) {
            builder.defaultOptions(org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .model(ctx.model())
                    .build());
        }
        String result = builder.build().prompt().user(prompt).call().content();
        return Arrays.stream(result.split("\n"))
                .map(this::normalizePlannerLine)
                .filter(s -> !s.isEmpty())
                .filter(this::isValidPlannedStep)
                .toList();
    }

    private String normalizePlannerLine(String line) {
        if (line == null) {
            return "";
        }
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("```") || "[".equals(s) || "]".equals(s)) {
            return "";
        }
        s = s.replaceFirst("^[-*\\d.)\\s]+", "").trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private boolean isValidPlannedStep(String step) {
        if (step == null || step.isBlank()) {
            return false;
        }
        if ("整理结果并回复用户".equals(step) || "回复用户".equals(step)) {
            return true;
        }
        if (step.startsWith("追问用户")) {
            return true;
        }
        int idx = step.indexOf(':');
        if (idx <= 0 || idx >= step.length() - 1) {
            return false;
        }
        String args = step.substring(idx + 1).trim();
        return args.startsWith("{") && args.endsWith("}");
    }

    /** StepExecutor —— 执行单个步骤（带工具调用） */
    private String invokeStepExecutor(ExecutionContext ctx, String stepDesc) {
        DirectToolStep directToolStep = parseDirectToolStep(stepDesc, ctx.cachedTools());
        if (directToolStep != null) {
            try {
                String toolResult = directToolStep.tool().call(directToolStep.toolInputJson());
                return toolResult != null ? toolResult : "(无结果)";
            } catch (Exception e) {
                log.warn("[StepExecutor] 直连工具执行失败: {} - {}", stepDesc, e.getMessage());
                return "执行失败: " + e.getMessage();
            }
        }

        String historyInfo = ctx.completedSteps().isEmpty() ? "" :
                ctx.completedSteps().stream()
                        .map(s -> "步骤「" + s.step() + "」结果: " + s.result())
                        .collect(Collectors.joining("\n"));
        String stepPrompt = """
                %s
                
                你正在执行以下步骤: %s
                
                用户原始问题: %s
                %s
                
                请严格执行这个步骤。
                - 如果需要调用工具，最多调用 1 次
                - 调用后直接输出这一步结果，不要继续调用其他工具
                - 只返回这一步的执行结果，不要返回最终回复
                - 不要评价工具是否存在、不要推断能力边界、不要编造失败原因
                - 如果工具调用成功，请尽量原样返回工具结果文本
                """.formatted(
                ctx.cachedPrompt(), stepDesc, ctx.enrichedMessage(),
                historyInfo.isEmpty() ? "" : "之前步骤的结果:\n" + historyInfo
        );

        ToolCallback[] scopedTools = selectToolsForStep(stepDesc, ctx.cachedTools());
        ToolCallback[] guardedTools = withSingleCallGuard(scopedTools, stepDesc);

        try {
            ChatClient.Builder builder = chatClientBuilder.clone();
            if (ctx.model() != null && !ctx.model().isBlank()) {
                builder.defaultOptions(org.springframework.ai.chat.prompt.ChatOptions.builder()
                        .model(ctx.model())
                        .build());
            }
            String result = builder.build()
                    .prompt()
                    .user(stepPrompt)
                    .toolCallbacks(guardedTools)
                    .call()
                    .content();
            return result != null ? result : "(无结果)";
        } catch (Exception e) {
            log.warn("[StepExecutor] 步骤执行失败: {} - {}", stepDesc, e.getMessage());
            return "执行失败: " + e.getMessage();
        }
    }

    private ToolCallback[] selectToolsForStep(String stepDesc, ToolCallback[] cachedTools) {
        if (cachedTools == null || cachedTools.length <= 1 || stepDesc == null || stepDesc.isBlank()) {
            return cachedTools;
        }

        String normalizedStep = stepDesc.trim();
        int colonIdx = normalizedStep.indexOf(':');
        String stepToolName = colonIdx > 0 ? normalizedStep.substring(0, colonIdx).trim() : normalizedStep;

        // 1) 优先精确匹配完整工具名
        for (ToolCallback tool : cachedTools) {
            String toolName = tool.getToolDefinition().name();
            if (stepToolName.equals(toolName)) {
                return new ToolCallback[]{tool};
            }
        }

        // 2) 匹配短名称（如 getWeatherForecast）
        for (ToolCallback tool : cachedTools) {
            String toolName = tool.getToolDefinition().name();
            if (stepToolName.equals(extractShortToolName(toolName)) || toolName.endsWith("_" + stepToolName)) {
                return new ToolCallback[]{tool};
            }
        }

        // 3) 兜底：按“最长命中”选择，避免 getWeatherForecast 被 getWeather 误命中
        ToolCallback best = null;
        int bestLen = -1;
        for (ToolCallback tool : cachedTools) {
            String toolName = tool.getToolDefinition().name();
            String shortName = extractShortToolName(toolName);
            boolean hit = normalizedStep.contains(toolName) || normalizedStep.contains(shortName);
            if (hit && toolName.length() > bestLen) {
                best = tool;
                bestLen = toolName.length();
            }
        }
        if (best != null) {
            return new ToolCallback[]{best};
        }

        log.warn("[StepExecutor] 步骤未匹配到具体工具，禁用工具调用避免重复下游请求。step={}", stepDesc);
        return new ToolCallback[0];
    }

    private String extractShortToolName(String fullToolName) {
        if (fullToolName == null || fullToolName.isBlank()) {
            return "";
        }
        int idx = fullToolName.lastIndexOf('_');
        return idx >= 0 && idx < fullToolName.length() - 1 ? fullToolName.substring(idx + 1) : fullToolName;
    }

    private record DirectToolStep(ToolCallback tool, String toolInputJson) {
    }

    private ToolCallback[] withSingleCallGuard(ToolCallback[] tools, String stepDesc) {
        if (tools == null || tools.length == 0) {
            return tools;
        }
        ToolCallback[] wrapped = new ToolCallback[tools.length];
        for (int i = 0; i < tools.length; i++) {
            ToolCallback delegate = tools[i];
            AtomicInteger callCount = new AtomicInteger(0);
            wrapped[i] = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return delegate.getToolDefinition();
                }

                @Override
                public ToolMetadata getToolMetadata() {
                    return delegate.getToolMetadata();
                }

                @Override
                public String call(String toolInput) {
                    return guardedCall(toolInput, null);
                }

                @Override
                public String call(String toolInput, ToolContext toolContext) {
                    return guardedCall(toolInput, toolContext);
                }

                private String guardedCall(String toolInput, ToolContext toolContext) {
                    int count = callCount.incrementAndGet();
                    if (count > 1) {
                        log.warn("[StepExecutor] 阻断同一步骤内重复工具调用，step='{}', tool='{}', callCount={}",
                                stepDesc, delegate.getToolDefinition().name(), count);
                        return "执行失败: 同一步骤只允许一次工具调用，请继续下一步骤。";
                    }
                    return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
                }
            };
        }
        return wrapped;
    }

    private int countStepExecutions(ExecutionContext ctx, String currentStep) {
        if (currentStep == null || currentStep.isBlank()) {
            return 0;
        }
        int count = 0;
        for (ExecutionContext.StepResult done : ctx.completedSteps()) {
            if (currentStep.equals(done.step())) {
                count++;
            }
        }
        return count;
    }

    /** Observer —— 观察步骤结果，判断是否需要 RePlan */
    private String invokeObserver(ExecutionContext ctx, String stepDesc, String stepResult) {
        if (!isStepFailureResult(stepResult)) {
            return "OK: 步骤执行成功，按计划继续执行。";
        }

        List<String> plan = ctx.currentPlan();
        int idx = ctx.stepIndex();
        String prompt = """
                你是一个任务观察者。请评估当前步骤的执行结果。
                
                用户问题: %s
                当前步骤: %s
                执行结果: %s
                剩余步骤: %s
                
                请用一句话评估：
                - 如果结果正常且剩余步骤合理，回复 "OK: [简要说明]"
                - 如果结果异常或需要调整计划，回复 "REPLAN: [原因和建议]"
                - 只依据“执行结果”是否失败来判断，禁止根据常识臆测当前日期/年份/时间
                - 如果执行结果本身没有报错，不得输出 REPLAN
                """.formatted(
                ctx.enrichedMessage(), stepDesc,
                stepResult.length() > 500 ? stepResult.substring(0, 500) + "..." : stepResult,
                idx + 1 < plan.size() ? String.join(", ", plan.subList(idx + 1, plan.size())) : "无"
        );

        try {
            return chatClientBuilder.build().prompt().user(prompt).call().content().trim();
        } catch (Exception e) {
            return "OK: 观察异常，继续执行";
        }
    }

    /** Summarizer —— 基于所有步骤结果生成最终回复 */
    private String invokeSummarizer(ExecutionContext ctx) {
        String stepsInfo = ctx.completedSteps().stream()
                .map(s -> "步骤「" + s.step() + "」结果:\n" + s.result())
                .collect(Collectors.joining("\n\n"));
        String primaryPrompt = """
                %s
                
                你是结果总结助手。请基于以下已执行步骤结果，给用户生成最终答复：
                - 答复要直接、简洁
                - 不要编造步骤结果中不存在的信息
                - 如果步骤里有失败信息，要如实说明并给出下一步建议

                用户问题：
                %s

                已执行步骤结果：
                %s
                """.formatted(ctx.cachedPrompt(), ctx.enrichedMessage(), stepsInfo);

        try {
            return callSummarizer(ctx.cachedPrompt(), primaryPrompt);
        } catch (Exception firstError) {
            log.warn("[Summarizer] 第一层总结失败，尝试轻量重试: {}", firstError.getMessage());
        }

        String retryPrompt = """
                请基于下面步骤结果，给出最终用户回复。
                要求：
                1) 不编造信息
                2) 失败要明确标注
                3) 120字以内

                用户问题：%s
                步骤结果：%s
                """.formatted(ctx.userMessage(), stepsInfo);

        try {
            return callSummarizer(null, retryPrompt);
        } catch (Exception secondError) {
            log.warn("[Summarizer] 第二层总结仍失败，返回确定性兜底结果: {}", secondError.getMessage());
            return buildFallbackSummary(ctx);
        }
    }

    private String callSummarizer(String systemPrompt, String userPrompt) {
        String content;
        if (systemPrompt == null || systemPrompt.isBlank()) {
            content = chatClientBuilder.build().prompt().user(userPrompt).call().content();
        } else {
            content = chatClientBuilder.build().prompt().system(systemPrompt).user(userPrompt).call().content();
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Summarizer returned empty content");
        }
        return content;
    }

    private String buildFallbackSummary(ExecutionContext ctx) {
        if (ctx.completedSteps().isEmpty()) {
            return "处理请求时发生网络波动，暂未拿到可用结果，请稍后重试。";
        }

        StringBuilder sb = new StringBuilder("本次请求已执行完成，因总结阶段网络超时，先返回步骤结果：\n\n");
        for (int i = 0; i < ctx.completedSteps().size(); i++) {
            ExecutionContext.StepResult step = ctx.completedSteps().get(i);
            sb.append(i + 1).append(". ").append(step.step()).append("\n");
            sb.append(step.result()).append("\n\n");
        }
        sb.append("（提示：可重试一次以获取更精炼的最终回复）");
        return sb.toString().trim();
    }

    private boolean isAskUserStep(String step) {
        return step != null && step.trim().startsWith(ASK_USER_PREFIX);
    }

    private String extractAskUserQuestion(String step) {
        for (String sep : new String[]{"：", ":", "，", ","}) {
            int idx = step.indexOf(sep);
            if (idx > 0 && idx < step.length() - 1) {
                String question = step.substring(idx + sep.length()).trim();
                if (!question.isEmpty()) return question;
            }
        }
        return "请问您能提供更多信息吗？例如城市名称、订单号等。";
    }

    private boolean needsReplan(String observation) {
        return observation != null && observation.toUpperCase().startsWith("REPLAN");
    }

    private boolean shouldReplan(String observation, String stepResult) {
        return needsReplan(observation) && isStepFailureResult(stepResult);
    }

    private boolean isStepFailureResult(String stepResult) {
        if (stepResult == null || stepResult.isBlank()) {
            return true;
        }
        String text = stepResult.toLowerCase();
        return text.startsWith("执行失败:")
                || text.contains("exception")
                || text.contains("readtimeoutexception")
                || text.contains("tool execution failed")
                || text.contains("\"iserror\":true");
    }

    /**
     * 将对话历史拼接到用户消息中，让 LLM 看到多轮上下文
     */
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
