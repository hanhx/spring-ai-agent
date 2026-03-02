package com.hhx.agi.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多意图执行器 —— 串行执行多个 Skill，跟踪追问状态，汇总结果
 *
 * 职责：
 *   - 串行执行多个 SkillIntent
 *   - 检测追问终止，保存剩余意图到待办队列
 *   - 所有 Skill 完成后 LLM 汇总
 *   - 下轮对话自动恢复待办意图
 */
@Component
public class MultiIntentExecutor {

    private static final Logger log = LoggerFactory.getLogger(MultiIntentExecutor.class);

    private final SkillExecutor executor;
    private final ChatClient.Builder chatClientBuilder;
    private final JdbcTemplate jdbc;

    public MultiIntentExecutor(SkillExecutor executor, ChatClient.Builder chatClientBuilder, JdbcTemplate jdbc) {
        this.executor = executor;
        this.chatClientBuilder = chatClientBuilder;
        this.jdbc = jdbc;
    }

    /** 取出并清除待办意图（从 H2） */
    public List<SkillIntent> popPending(String conversationId) {
        List<SkillIntent> pending = jdbc.query(
                "SELECT skill_name, sub_task FROM pending_intents WHERE conversation_id = ? ORDER BY id",
                (rs, i) -> new SkillIntent(rs.getString("skill_name"), rs.getString("sub_task")),
                conversationId);
        if (!pending.isEmpty()) {
            jdbc.update("DELETE FROM pending_intents WHERE conversation_id = ?", conversationId);
            log.info("[MultiIntent] 从 H2 取出 {} 个待办意图", pending.size());
        }
        return pending.isEmpty() ? null : pending;
    }

    /** 保存待办意图到 H2 */
    private void savePending(String conversationId, List<SkillIntent> intents) {
        jdbc.update("DELETE FROM pending_intents WHERE conversation_id = ?", conversationId);
        for (SkillIntent intent : intents) {
            jdbc.update("INSERT INTO pending_intents (conversation_id, skill_name, sub_task) VALUES (?, ?, ?)",
                    conversationId, intent.skillName(), intent.subTask());
        }
        log.info("[MultiIntent] 保存 {} 个待办意图到 H2", intents.size());
    }

    /** 合并新意图与待办意图（按 skillName 去重） */
    public List<SkillIntent> mergeIntents(List<SkillIntent> newIntents, List<SkillIntent> pending) {
        List<SkillIntent> merged = new ArrayList<>(newIntents);
        if (pending != null) {
            for (SkillIntent pi : pending) {
                boolean dup = merged.stream().anyMatch(i -> i.skillName().equals(pi.skillName()));
                if (!dup) merged.add(pi);
            }
        }
        return merged;
    }

    /**
     * 串行执行多个意图，追问时保存待办，完成后汇总
     */
    public Flux<PlanActionEvent> execute(String conversationId, List<SkillIntent> intents,
                                          Map<String, SkillDefinition> skillMap, SkillDefinition fallback,
                                          String userId) {
        log.info("[MultiIntent] 共 {} 个子任务", intents.size());
        int total = intents.size();

        final boolean[] askUserDetected = {false};
        final List<String> skillResults = new ArrayList<>();

        List<Flux<PlanActionEvent>> fluxes = new ArrayList<>();
        fluxes.add(Flux.just(PlanActionEvent.planning(
                String.format("💡 识别到 %d 个任务，开始逐个处理...", total))));

        for (int i = 0; i < intents.size(); i++) {
            final int idx = i + 1;
            final int intentIndex = i;
            SkillIntent intent = intents.get(i);
            SkillDefinition skill = skillMap.getOrDefault(intent.skillName(), fallback);
            if (skill == null) {
                log.warn("[MultiIntent] Skill [{}] 未找到，跳过", intent.skillName());
                continue;
            }
            final String skillName = skill.name();
            final String subTask = intent.subTask();

            fluxes.add(Flux.defer(() -> {
                if (askUserDetected[0]) {
                    savePending(conversationId, new ArrayList<>(intents.subList(intentIndex, intents.size())));
                    return Flux.empty();
                }

                final boolean[] hasAction = {false};
                return Flux.concat(
                        Flux.just(PlanActionEvent.skillStart(idx, total, skillName, subTask)),
                        executor.planAndExecute(skill, conversationId, subTask, userId)
                                .doOnNext(event -> {
                                    if ("action".equals(event.type())) hasAction[0] = true;
                                    if ("result".equals(event.type()) && event.content() != null) {
                                        skillResults.add("【" + subTask + "】\n" + event.content());
                                    }
                                })
                                .doOnComplete(() -> {
                                    if (!hasAction[0]) {
                                        askUserDetected[0] = true;
                                        log.info("[MultiIntent] Skill [{}] 追问终止", skillName);
                                    }
                                })
                );
            }));
        }

        // 汇总阶段
        fluxes.add(Flux.defer(() -> {
            if (askUserDetected[0] || skillResults.size() <= 1) return Flux.empty();
            log.info("[MultiIntent] 汇总 {} 个结果", skillResults.size());
            return Flux.concat(
                    Flux.just(PlanActionEvent.planning("📝 正在汇总所有任务结果...")),
                    Mono.fromCallable(() -> {
                        String allResults = String.join("\n\n---\n\n", skillResults);
                        String prompt = """
                                你是一个智能助手。用户一次提出了多个问题，以下是各个子任务的执行结果。
                                请将所有结果整合成一个完整、连贯的回复，不要遗漏任何子任务的信息。
                                使用中文回复，语气友好专业。
                                
                                各子任务结果：
                                %s
                                
                                请输出整合后的完整回复：
                                """.formatted(allResults);
                        return PlanActionEvent.result(
                                chatClientBuilder.build().prompt().user(prompt).call().content());
                    }).subscribeOn(Schedulers.boundedElastic()),
                    Flux.just(PlanActionEvent.done())
            );
        }));

        return Flux.concat(fluxes);
    }
}
