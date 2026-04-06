package com.hhx.agi.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待办意图内存存储 —— 替代数据库存储，轻量无依赖
 *
 * 参考 Claude Code todo_reminders 机制：任务状态保存在内存中，
 * 每次新对话开始时注入到上下文，让模型感知未完成的任务。
 */
@Component
public class PendingIntentStore {

    private static final Logger log = LoggerFactory.getLogger(PendingIntentStore.class);

    private final ConcurrentHashMap<String, List<SkillIntent>> store = new ConcurrentHashMap<>();

    /** 取出并清除待办意图 */
    public List<SkillIntent> popPending(String conversationId) {
        List<SkillIntent> intents = store.remove(conversationId);
        if (intents != null && !intents.isEmpty()) {
            log.info("[PendingIntentStore] 恢复 {} 个待办意图, conversationId={}", intents.size(), conversationId);
            return intents;
        }
        return List.of();
    }

    /** 保存待办意图 */
    public void savePending(String conversationId, List<SkillIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            store.remove(conversationId);
        } else {
            store.put(conversationId, new ArrayList<>(intents));
            log.info("[PendingIntentStore] 保存 {} 个待办意图, conversationId={}", intents.size(), conversationId);
        }
    }

    /** 是否有待办意图 */
    public boolean hasPending(String conversationId) {
        List<SkillIntent> intents = store.get(conversationId);
        return intents != null && !intents.isEmpty();
    }
}
