package com.hhx.agi.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final PendingIntentStore pendingIntentStore;

    public MultiIntentExecutor(SkillExecutor executor, ChatClient.Builder chatClientBuilder, PendingIntentStore pendingIntentStore) {
        this.executor = executor;
        this.chatClientBuilder = chatClientBuilder;
        this.pendingIntentStore = pendingIntentStore;
    }

    /** 取出并清除待办意图（内存存储） */
    public List<SkillIntent> popPending(String conversationId) {
        List<SkillIntent> pending = pendingIntentStore.popPending(conversationId);
        return pending.isEmpty() ? null : pending;
    }

    /** 保存待办意图（内存存储） */
    private void savePending(String conversationId, List<SkillIntent> intents) {
        pendingIntentStore.savePending(conversationId, intents);
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
     * 并发执行多个意图，完成后汇总结果
     *
     * 并发策略：单请求内的多个意图均视为独立任务，通过 Flux.merge 并发运行。
     * 追问检测使用结构化 ask_user 事件而非推断 boolean 状态。
     */
    public Flux<PlanActionEvent> execute(String conversationId, List<SkillIntent> intents,
                                          Map<String, SkillDefinition> skillMap, SkillDefinition fallback, String model, String userId) {
        log.info("[MultiIntent] 共 {} 个子任务, model: {}, userId: {}", intents.size(), model, userId);
        int total = intents.size();

        AtomicBoolean askUserDetected = new AtomicBoolean(false);
        CopyOnWriteArrayList<String> skillResults = new CopyOnWriteArrayList<>();

        // 构建每个意图的执行 Flux
        List<Flux<PlanActionEvent>> skillFluxes = new ArrayList<>();
        for (int i = 0; i < intents.size(); i++) {
            final int idx = i + 1;
            SkillIntent intent = intents.get(i);
            SkillDefinition skill = skillMap.getOrDefault(intent.skillName(), fallback);
            if (skill == null) {
                log.warn("[MultiIntent] Skill [{}] 未找到，跳过", intent.skillName());
                continue;
            }
            final String skillName = skill.name();
            final String subTask = intent.subTask();

            skillFluxes.add(
                Flux.concat(
                    Flux.just(PlanActionEvent.skillStart(idx, total, skillName, subTask)),
                    executor.planAndExecute(skill, conversationId, subTask, model, userId)
                        .doOnNext(event -> {
                            if ("ask_user".equals(event.type())) {
                                askUserDetected.set(true);
                                log.info("[MultiIntent] Skill [{}] 追问终止", skillName);
                            }
                            if ("result".equals(event.type()) && event.content() != null) {
                                skillResults.add("【" + subTask + "】\n" + event.content());
                            }
                        })
                )
            );
        }

        Flux<PlanActionEvent> header = Flux.just(
            PlanActionEvent.planning(String.format("💡 识别到 %d 个任务，并发处理中...", total))
        );

        // 并发运行所有 Skill
        Flux<PlanActionEvent> allSkills = Flux.merge(skillFluxes);

        // 汇总阶段：所有 Skill 完成后才运行
        Flux<PlanActionEvent> aggregation = Flux.defer(() -> {
            if (askUserDetected.get() || skillResults.size() <= 1) return Flux.empty();
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
        });

        return Flux.concat(header, allSkills, aggregation);
    }
}
