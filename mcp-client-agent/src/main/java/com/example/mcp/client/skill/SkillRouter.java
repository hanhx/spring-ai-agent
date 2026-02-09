package com.example.mcp.client.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Skill 路由器 —— 基于 SKILL.md 的 description 做 LLM 意图识别，分发到对应 Skill 执行
 * 支持多意图：一条用户消息可包含多个意图，串行执行后汇总结果
 *
 * 架构：
 *   用户请求 → Embedding Top-K 检索相关 Skill
 *           → SkillRouter (LLM 多意图识别，仅候选 Skill)
 *           → SkillExecutor × N (每个意图独立 Plan&Execute)
 *           → 汇总结果回复
 */
@Component
public class SkillRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);

    private final ChatClient.Builder chatClientBuilder;
    private final Map<String, SkillDefinition> skillMap;
    private final SkillDefinition fallbackSkill;
    private final SkillExecutor executor;
    private final MultiIntentExecutor multiIntentExecutor;
    private final ChatMemory chatMemory;
    private final SkillEmbeddingIndex embeddingIndex;

    @Autowired
    public SkillRouter(ChatClient.Builder chatClientBuilder, SkillLoader loader, SkillExecutor executor,
                       MultiIntentExecutor multiIntentExecutor, ChatMemory chatMemory,
                       SkillEmbeddingIndex embeddingIndex) {
        this.chatClientBuilder = chatClientBuilder;
        this.executor = executor;
        this.multiIntentExecutor = multiIntentExecutor;
        this.chatMemory = chatMemory;
        this.embeddingIndex = embeddingIndex;

        List<SkillDefinition> skills = loader.getSkills();
        this.skillMap = skills.stream()
                .collect(Collectors.toMap(SkillDefinition::name, Function.identity()));
        this.fallbackSkill = skillMap.get("chitchat");
    }

    @PostConstruct
    public void init() {
        log.info("========== Skill Router 初始化 ==========");
        log.info("已加载 {} 个 Skills (from SKILL.md):", skillMap.size());
        skillMap.forEach((name, skill) ->
                log.info("  - [{}] {} (工具: {})", name, skill.description(), skill.allowedTools()));
        log.info("兜底 Skill: {}", fallbackSkill != null ? fallbackSkill.name() : "无");
        log.info("==========================================");
    }

    /**
     * 路由并执行 —— 先识别意图（支持多意图），再分发到对应 Skill
     */
    public SkillResponse route(String conversationId, String userMessage) {
        log.info("[SkillRouter] 收到请求: {}", userMessage);

        List<SkillIntent> intents = identifySkills(conversationId, userMessage);
        log.info("[SkillRouter] 识别到 {} 个意图: {}", intents.size(), intents);

        if (intents.size() == 1) {
            // 单意图：直接执行
            SkillIntent intent = intents.get(0);
            SkillDefinition skill = skillMap.getOrDefault(intent.skillName(), fallbackSkill);
            if (skill == null) {
                return new SkillResponse("router", "抱歉，系统暂时无法处理您的请求。");
            }
            return executor.execute(skill, conversationId, intent.subTask());
        }

        // 多意图：串行执行，合并结果
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < intents.size(); i++) {
            SkillIntent intent = intents.get(i);
            SkillDefinition skill = skillMap.getOrDefault(intent.skillName(), fallbackSkill);
            if (skill == null) continue;
            SkillResponse resp = executor.execute(skill, conversationId, intent.subTask());
            combined.append("### 任务 ").append(i + 1).append(": ").append(intent.subTask()).append("\n");
            combined.append(resp.content()).append("\n\n");
        }
        return new SkillResponse("multi", combined.toString().trim());
    }

    /**
     * Plan & Action 流式路由 —— 支持多意图 + 待办意图恢复
     */
    public Flux<PlanActionEvent> streamRoute(String conversationId, String userMessage) {
        log.info("[SkillRouter] 流式请求: {}", userMessage);

        return Flux.concat(
                Flux.just(PlanActionEvent.planning("🤔 正在理解您的问题...")),

                Flux.defer(() -> {
                    List<SkillIntent> pending = multiIntentExecutor.popPending(conversationId);
                    List<SkillIntent> newIntents = identifySkills(conversationId, userMessage);
                    log.info("[SkillRouter] 识别到 {} 个新意图: {}", newIntents.size(), newIntents);

                    List<SkillIntent> allIntents = multiIntentExecutor.mergeIntents(newIntents, pending);
                    if (pending != null && !pending.isEmpty()) {
                        log.info("[SkillRouter] 合并待办意图后共 {} 个: {}", allIntents.size(), allIntents);
                    }

                    if (allIntents.isEmpty()) {
                        return Flux.just(PlanActionEvent.error("抱歉，系统暂时无法处理您的请求。"), PlanActionEvent.done());
                    }

                    // 单意图：直接执行
                    if (allIntents.size() == 1) {
                        SkillIntent intent = allIntents.get(0);
                        SkillDefinition skill = skillMap.getOrDefault(intent.skillName(), fallbackSkill);
                        if (skill == null) {
                            return Flux.just(PlanActionEvent.error("抱歉，系统暂时无法处理您的请求。"), PlanActionEvent.done());
                        }
                        return Flux.concat(
                                Flux.just(PlanActionEvent.planning("💡 已理解，正在规划执行方案...")),
                                executor.planAndExecute(skill, conversationId, intent.subTask())
                        );
                    }

                    // 多意图：委托 MultiIntentExecutor
                    return multiIntentExecutor.execute(conversationId, allIntents, skillMap, fallbackSkill);
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        );
    }

    /**
     * 多意图识别 —— 返回一个或多个 SkillIntent
     * LLM 输出格式：每行一个 "skill_name|子任务描述"，单意图时也兼容只返回 skill 名称
     */
    private List<SkillIntent> identifySkills(String conversationId, String userMessage) {
        try {
            // 获取对话历史，让 Router 能识别上下文延续的意图
            List<Message> history = chatMemory.get(conversationId);
            String historyContext = "";
            if (history != null && !history.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n## 对话历史（从早到晚）\n");
                int start = Math.max(0, history.size() - 16);
                for (int i = start; i < history.size(); i++) {
                    Message msg = history.get(i);
                    String role = msg.getMessageType().name().toLowerCase();
                    String text = msg.getText();
                    if ("assistant".equals(role) && text != null && text.length() > 150) {
                        text = text.substring(0, 150) + "...";
                    }
                    sb.append(role).append(": ").append(text).append("\n");
                }
                sb.append("\n请结合对话历史判断用户当前消息的意图。如果用户在补充上一轮的信息，应该路由到同一个 Skill。");
                historyContext = sb.toString();
            }

            // Embedding Top-K 检索候选 Skill，动态构建 Prompt
            List<SkillDefinition> candidates = embeddingIndex.retrieveTopK(userMessage);
            String systemPrompt = buildRouterPrompt(candidates);

            String result = chatClientBuilder.build().prompt()
                    .system(systemPrompt)
                    .user(userMessage + historyContext)
                    .call()
                    .content()
                    .trim();

            log.info("[SkillRouter] LLM 意图识别原始返回: {}", result);
            return parseIntents(result, userMessage);
        } catch (Exception e) {
            log.error("[SkillRouter] 意图识别出错: {}", e.getMessage(), e);
            return List.of(new SkillIntent("chitchat", userMessage));
        }
    }

    /**
     * 解析 LLM 返回的意图列表
     * 支持格式：
     *   - 单行 skill 名称（兼容旧格式）
     *   - 多行 "skill_name|子任务描述"
     */
    private List<SkillIntent> parseIntents(String llmOutput, String originalMessage) {
        List<SkillIntent> intents = new ArrayList<>();
        String[] lines = llmOutput.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.contains("|")) {
                // 多意图格式: skill_name|子任务描述
                String[] parts = trimmed.split("\\|", 2);
                String skillName = parts[0].trim().toLowerCase();
                String subTask = parts.length > 1 ? parts[1].trim() : originalMessage;
                String matched = matchSkillName(skillName);
                if (matched != null) {
                    intents.add(new SkillIntent(matched, subTask));
                }
            } else {
                // 单意图格式: 只有 skill 名称
                String skillName = trimmed.toLowerCase();
                String matched = matchSkillName(skillName);
                if (matched != null) {
                    intents.add(new SkillIntent(matched, originalMessage));
                }
            }
        }

        if (intents.isEmpty()) {
            log.warn("[SkillRouter] 无法解析意图，LLM 返回: '{}', 使用兜底", llmOutput);
            intents.add(new SkillIntent("chitchat", originalMessage));
        }

        return intents;
    }

    /**
     * 匹配 Skill 名称（精确匹配 + 模糊匹配）
     */
    private String matchSkillName(String name) {
        if (skillMap.containsKey(name)) {
            return name;
        }
        for (String key : skillMap.keySet()) {
            if (name.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private String buildRouterPrompt(List<SkillDefinition> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个意图识别路由器。你的任务是：根据用户的消息，判断应该由哪个 Skill 来处理。
                
                ## 规则
                - 如果用户消息只包含一个意图，返回一行：skill名称
                - 如果用户消息包含多个不同意图，每行返回一个：skill名称|子任务描述
                - 不要解释，不要加标点，严格按格式返回
                - 如果无法确定，返回 chitchat
                - 同一个 Skill 的多个参数不算多意图（如"查北京和上海天气"是一个 weather 意图）
                
                ## 可用的 Skills
                """);

        for (SkillDefinition skill : skills) {
            sb.append("- **").append(skill.name()).append("**: ").append(skill.description()).append("\n");
        }

        // 自动生成单意图示例：每个 Skill 一条
        sb.append("\n## 单意图示例（只返回 skill 名称）\n");
        for (SkillDefinition skill : skills) {
            sb.append("用户: \"关于").append(skill.description()).append("\" → ").append(skill.name()).append("\n");
        }

        // 自动生成多意图示例：取前几个 Skill 两两组合
        sb.append("\n## 多意图示例（每行 skill名称|子任务描述）\n");
        List<SkillDefinition> nonFallback = skills.stream()
                .filter(s -> !"chitchat".equals(s.name()))
                .toList();
        int exampleCount = 0;
        for (int i = 0; i < nonFallback.size() && exampleCount < 2; i++) {
            for (int j = i + 1; j < nonFallback.size() && exampleCount < 2; j++) {
                SkillDefinition a = nonFallback.get(i);
                SkillDefinition b = nonFallback.get(j);
                sb.append("用户: \"帮我处理一下").append(a.description()).append("，还有").append(b.description()).append("\" →\n");
                sb.append(a.name()).append("|").append(a.description()).append("\n");
                sb.append(b.name()).append("|").append(b.description()).append("\n\n");
                exampleCount++;
            }
        }

        return sb.toString();
    }

    /**
     * 获取所有已注册的 Skill 信息
     */
    public Map<String, String> getSkillInfo() {
        return skillMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().description()));
    }
}
