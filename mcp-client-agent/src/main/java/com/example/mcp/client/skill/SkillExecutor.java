package com.example.mcp.client.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /**
     * Plan-and-Execute 流式模式 —— Plan → Execute → Observe → (RePlan?) → Final Answer
     */
    public Flux<PlanActionEvent> planAndExecute(SkillDefinition skill, String conversationId, String userMessage) {
        log.info("[SkillExecutor] Plan&Execute Skill [{}]，用户消息: {}", skill.name(), userMessage);
        chatMemory.add(conversationId, new UserMessage(userMessage));

        String enrichedMessage = enrichWithHistory(conversationId, userMessage);
        ExecutionContext ctx = new ExecutionContext(skill, conversationId, userMessage, enrichedMessage);

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

        String currentStep = ctx.currentStep();
        int displayStep = ctx.currentStepDisplay();
        int totalSteps = ctx.totalSteps();

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
                    return PlanActionEvent.actionDone(displayStep, totalSteps, currentStep, result);
                }).subscribeOn(Schedulers.boundedElastic()),

                // 观察 + 可能 replan + 递归
                Flux.defer(() -> {
                    var lastResult = ctx.completedSteps().get(ctx.completedSteps().size() - 1);
                    String observation = invokeObserver(ctx, currentStep, lastResult.result());
                    log.info("[Observe] Step {}: {}", displayStep, observation);

                    List<PlanActionEvent> events = new ArrayList<>();
                    events.add(PlanActionEvent.observe(displayStep, observation));

                    if (needsReplan(observation) && ctx.replanCount() < MAX_REPLAN_ROUNDS) {
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
                """.formatted(toolsInfo, ctx.enrichedMessage(), completedInfo);

        String result = chatClientBuilder.build().prompt().user(prompt).call().content();
        return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.startsWith("#"))
                .toList();
    }

    /** StepExecutor —— 执行单个步骤（带工具调用） */
    private String invokeStepExecutor(ExecutionContext ctx, String stepDesc) {
        String historyInfo = ctx.completedSteps().isEmpty() ? "" :
                ctx.completedSteps().stream()
                        .map(s -> "步骤「" + s.step() + "」结果: " + s.result())
                        .collect(Collectors.joining("\n"));
        String stepPrompt = """
                %s
                
                你正在执行以下步骤: %s
                
                用户原始问题: %s
                %s
                
                请执行这个步骤。如果需要调用工具，请调用。只返回这一步的执行结果，不要返回最终回复。
                """.formatted(
                ctx.cachedPrompt(), stepDesc, ctx.enrichedMessage(),
                historyInfo.isEmpty() ? "" : "之前步骤的结果:\n" + historyInfo
        );

        try {
            String result = chatClientBuilder.build()
                    .prompt()
                    .user(stepPrompt)
                    .toolCallbacks(ctx.cachedTools())
                    .call()
                    .content();
            return result != null ? result : "(无结果)";
        } catch (Exception e) {
            log.warn("[StepExecutor] 步骤执行失败: {} - {}", stepDesc, e.getMessage());
            return "执行失败: " + e.getMessage();
        }
    }

    /** Observer —— 观察步骤结果，判断是否需要 RePlan */
    private String invokeObserver(ExecutionContext ctx, String stepDesc, String stepResult) {
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
        String prompt = """
                %s
                
                用户问题: %s
                
                以下是已执行步骤及其结果：
                %s
                
                请根据以上所有步骤的结果，生成对用户问题的最终完整回复。
                """.formatted(ctx.cachedPrompt(), ctx.enrichedMessage(), stepsInfo);

        return chatClientBuilder.build().prompt().system(ctx.cachedPrompt()).user(prompt).call().content();
    }

    // ==================== 辅助方法 ====================

    private boolean isAskUserStep(String step) {
        return step.startsWith(ASK_USER_PREFIX) || step.startsWith("等待用户");
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
