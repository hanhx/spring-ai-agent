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

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Skill 路由器 —— 基于 SKILL.md 的 description 做 LLM 意图识别，分发到对应 Skill 执行
 *
 * 架构：
 *   用户请求 → SkillRouter (LLM 意图识别)
 *              → SkillExecutor (加载 SKILL.md 为 prompt + 绑定 MCP 工具执行)
 */
@Component
public class SkillRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);

    private final ChatClient routerClient;
    private final Map<String, SkillDefinition> skillMap;
    private final SkillDefinition fallbackSkill;
    private final SkillExecutor executor;
    private final ChatMemory chatMemory;

    @Autowired
    public SkillRouter(ChatClient.Builder chatClientBuilder, SkillLoader loader, SkillExecutor executor,
                       ChatMemory chatMemory) {
        this.executor = executor;
        this.chatMemory = chatMemory;

        List<SkillDefinition> skills = loader.getSkills();
        this.skillMap = skills.stream()
                .collect(Collectors.toMap(SkillDefinition::name, Function.identity()));
        this.fallbackSkill = skillMap.get("chitchat");

        String systemPrompt = buildRouterPrompt(skills);
        this.routerClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .build();
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
     * 路由并执行 —— 先识别意图，再分发到对应 Skill
     */
    public SkillResponse route(String conversationId, String userMessage) {
        log.info("[SkillRouter] 收到请求: {}", userMessage);

        // Step 1: LLM 意图识别（带对话历史，支持上下文延续）
        String skillName = identifySkill(conversationId, userMessage);
        log.info("[SkillRouter] 意图识别结果: → Skill [{}]", skillName);

        // Step 2: 查找 Skill 定义
        SkillDefinition skill = skillMap.getOrDefault(skillName, fallbackSkill);
        if (skill == null) {
            return new SkillResponse("router", "抱歉，系统暂时无法处理您的请求。");
        }

        // Step 3: 执行 Skill（传递 conversationId 用于 memory）
        log.info("[SkillRouter] 分发到 Skill: [{}] {}", skill.name(), skill.description());
        return executor.execute(skill, conversationId, userMessage);
    }

    /**
     * Plan & Action 流式路由 —— 意图识别 + Skill 执行都在 Flux 内异步完成
     */
    public Flux<PlanActionEvent> streamRoute(String conversationId, String userMessage) {
        log.info("[SkillRouter] 流式请求: {}", userMessage);

        return Flux.concat(
                // 1. 立即推送“正在理解”
                Flux.just(PlanActionEvent.planning("🤔 正在理解您的问题...")),

                // 2. 意图识别（带对话历史）→ 推送“已理解” → 执行 planAndExecute
                Flux.defer(() -> {
                    String skillName = identifySkill(conversationId, userMessage);
                    log.info("[SkillRouter] 意图识别结果: → Skill [{}]", skillName);

                    SkillDefinition skill = skillMap.getOrDefault(skillName, fallbackSkill);
                    if (skill == null) {
                        return Flux.just(
                                PlanActionEvent.error("抱歉，系统暂时无法处理您的请求。"),
                                PlanActionEvent.done()
                        );
                    }

                    return Flux.concat(
                            Flux.just(PlanActionEvent.planning("💡 已理解，正在规划执行方案...")),
                            executor.planAndExecute(skill, conversationId, userMessage)
                    );
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        );
    }

    private String identifySkill(String conversationId, String userMessage) {
        try {
            // 获取对话历史，让 Router 能识别上下文延续的意图（如“上海”延续上一轮的天气查询）
            List<Message> history = chatMemory.get(conversationId);
            String historyContext = "";
            if (history != null && !history.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n## 对话历史（从早到晚）\n");
                int start = Math.max(0, history.size() - 16);
                for (int i = start; i < history.size(); i++) {
                    Message msg = history.get(i);
                    String role = msg.getMessageType().name().toLowerCase();
                    String text = msg.getText();
                    // 截断过长的助手回复，只保留摘要
                    if ("assistant".equals(role) && text != null && text.length() > 150) {
                        text = text.substring(0, 150) + "...";
                    }
                    sb.append(role).append(": ").append(text).append("\n");
                }
                sb.append("\n请结合对话历史判断用户当前消息的意图。如果用户在补充上一轮的信息，应该路由到同一个 Skill。");
                historyContext = sb.toString();
            }

            String result = routerClient.prompt()
                    .user(userMessage + historyContext)
                    .call()
                    .content()
                    .trim()
                    .toLowerCase();

            if (skillMap.containsKey(result)) {
                return result;
            }

            // 模糊匹配
            for (String name : skillMap.keySet()) {
                if (result.contains(name)) {
                    return name;
                }
            }

            log.warn("[SkillRouter] 无法匹配 Skill，LLM 返回: '{}', 使用兜底", result);
            return "chitchat";
        } catch (Exception e) {
            log.error("[SkillRouter] 意图识别出错: {}", e.getMessage(), e);
            return "chitchat";
        }
    }

    private String buildRouterPrompt(List<SkillDefinition> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个意图识别路由器。你的唯一任务是：根据用户的消息，判断应该由哪个 Skill 来处理。
                
                ## 规则
                - 你只需要返回一个 skill 名称，不要返回任何其他内容
                - 不要解释，不要加标点，只返回 skill 名称
                - 如果无法确定，返回 chitchat
                
                ## 可用的 Skills
                """);

        for (SkillDefinition skill : skills) {
            sb.append("- **").append(skill.name()).append("**: ").append(skill.description()).append("\n");
        }

        sb.append("""
                
                ## 示例
                用户: "北京天气怎么样" → weather
                用户: "查一下我的订单" → order-query
                用户: "张三有哪些订单" → order-query
                用户: "帮我退款" → refund
                用户: "我要退货，订单号ORD123" → refund
                用户: "物流到哪了" → logistics
                用户: "帮我查快递" → logistics
                用户: "有哪些商品" → data-analysis
                用户: "统计一下销售额" → data-analysis
                用户: "你好" → chitchat
                用户: "你是谁" → chitchat
                """);

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
