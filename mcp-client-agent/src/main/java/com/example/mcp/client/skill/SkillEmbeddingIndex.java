package com.example.mcp.client.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Skill 语义索引 —— 启动时对所有 Skill 的 description 做 Embedding，
 * 运行时用余弦相似度检索 Top-K 最相关的 Skill，减少 Router Prompt 的 token 消耗。
 *
 * 当 Skill 数量较少（<= topK）时自动退化为全量返回，无额外开销。
 */
@Component
public class SkillEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingIndex.class);

    private static final int DEFAULT_TOP_K = 5;

    private final EmbeddingModel embeddingModel;
    private final SkillLoader skillLoader;

    /** Skill name → embedding vector */
    private final Map<String, float[]> skillVectors = new LinkedHashMap<>();
    private final Map<String, SkillDefinition> skillMap = new LinkedHashMap<>();
    private boolean indexReady = false;

    public SkillEmbeddingIndex(EmbeddingModel embeddingModel, SkillLoader skillLoader) {
        this.embeddingModel = embeddingModel;
        this.skillLoader = skillLoader;
    }

    @PostConstruct
    public void buildIndex() {
        try {
            List<SkillDefinition> skills = skillLoader.getSkills();
            if (skills.isEmpty()) {
                log.warn("[SkillEmbeddingIndex] 没有 Skill 可索引");
                return;
            }

            // 收集所有 description 做批量 embedding
            List<String> texts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SkillDefinition skill : skills) {
                names.add(skill.name());
                texts.add(skill.name() + ": " + skill.description());
                skillMap.put(skill.name(), skill);
            }

            log.info("[SkillEmbeddingIndex] 正在为 {} 个 Skill 生成 Embedding...", texts.size());
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);

            for (int i = 0; i < names.size(); i++) {
                float[] vector = response.getResults().get(i).getOutput();
                skillVectors.put(names.get(i), vector);
            }

            indexReady = true;
            log.info("[SkillEmbeddingIndex] 索引构建完成，共 {} 个 Skill，向量维度: {}",
                    skillVectors.size(),
                    skillVectors.values().iterator().next().length);
        } catch (Exception e) {
            log.error("[SkillEmbeddingIndex] 索引构建失败，将降级为全量路由: {}", e.getMessage());
            indexReady = false;
        }
    }

    /**
     * 检索与用户消息最相关的 Top-K 个 Skill
     * 如果索引未就绪或 Skill 数量 <= topK，返回全部 Skill
     * chitchat 始终包含在结果中作为兜底
     */
    public List<SkillDefinition> retrieveTopK(String userMessage, int topK) {
        List<SkillDefinition> allSkills = skillLoader.getSkills();

        // 降级：索引未就绪或 Skill 数量不多，直接返回全部
        if (!indexReady || allSkills.size() <= topK) {
            return allSkills;
        }

        try {
            // 对用户消息做 embedding
            float[] queryVector = embeddingModel.embed(userMessage);

            // 计算余弦相似度并排序
            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (Map.Entry<String, float[]> entry : skillVectors.entrySet()) {
                double sim = cosineSimilarity(queryVector, entry.getValue());
                scores.add(Map.entry(entry.getKey(), sim));
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            // 取 Top-K
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(topK, scores.size()); i++) {
                selected.add(scores.get(i).getKey());
            }
            // 确保 chitchat 始终在候选中
            selected.add("chitchat");

            List<SkillDefinition> result = new ArrayList<>();
            for (SkillDefinition skill : allSkills) {
                if (selected.contains(skill.name())) {
                    result.add(skill);
                }
            }

            log.info("[SkillEmbeddingIndex] 检索 Top-{}: {} (from {} skills)",
                    topK, selected, allSkills.size());
            return result;
        } catch (Exception e) {
            log.error("[SkillEmbeddingIndex] 检索失败，降级为全量: {}", e.getMessage());
            return allSkills;
        }
    }

    /**
     * 使用默认 Top-K
     */
    public List<SkillDefinition> retrieveTopK(String userMessage) {
        return retrieveTopK(userMessage, DEFAULT_TOP_K);
    }

    public boolean isIndexReady() {
        return indexReady;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }
}
