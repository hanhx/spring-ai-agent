package com.hhx.agi.application.agent;

import com.hhx.agi.infra.client.DashScopeEmbeddingClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 候选检索 —— 优先使用 Embedding 检索 Top-K（若启用且可用），
 * 否则降级到词法打分（名称命中 + token 重叠 + bigram 相似度）。
 *
 * 当 Skill 数量较少（<= topK）时自动退化为全量返回，无额外开销。
 */
@Component
public class SkillEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingIndex.class);

    private static final int DEFAULT_TOP_K = 5;
    private final DashScopeEmbeddingClient embeddingClient;
    private final SkillLoader skillLoader;
    @Value("${dashscope.embedding.enabled:true}")
    private boolean embeddingEnabled;
    private final Map<String, float[]> skillVectors = new LinkedHashMap<>();
    private boolean indexReady = false;

    public SkillEmbeddingIndex(
            DashScopeEmbeddingClient embeddingClient,
            SkillLoader skillLoader) {
        this.embeddingClient = embeddingClient;
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

            if (!embeddingEnabled) {
                log.info("[SkillEmbeddingIndex] Embedding 未启用，使用词法 Top-K 路由");
                indexReady = false;
                return;
            }

            if (skills.size() <= DEFAULT_TOP_K) {
                log.info("[SkillEmbeddingIndex] Skill 数量({}) <= topK({})，直接走全量/词法路由", skills.size(), DEFAULT_TOP_K);
                indexReady = false;
                return;
            }

            List<String> texts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SkillDefinition skill : skills) {
                names.add(skill.name());
                texts.add(skill.name() + ": " + skill.description());
            }

            log.info("[SkillEmbeddingIndex] 正在为 {} 个 Skill 生成 Embedding (DashScope API)...", texts.size());
            List<float[]> vectors = embeddingClient.embedTexts(texts);

            skillVectors.clear();
            for (int i = 0; i < names.size(); i++) {
                float[] vector = vectors.get(i);
                skillVectors.put(names.get(i), vector);
            }

            indexReady = !skillVectors.isEmpty();
            if (indexReady) {
                log.info("[SkillEmbeddingIndex] 索引构建完成，共 {} 个 Skill，向量维度: {}",
                        skillVectors.size(),
                        skillVectors.values().iterator().next().length);
            }
        } catch (Exception e) {
            log.warn("[SkillEmbeddingIndex] 索引构建失败，降级为词法 Top-K: {}", e.getMessage());
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

        if (allSkills.size() <= topK) {
            return allSkills;
        }

        if (!embeddingEnabled || !indexReady) {
            return retrieveTopKByLexical(userMessage, allSkills, topK);
        }

        try {
            float[] queryVector = embeddingClient.embedSingleText(userMessage);

            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (Map.Entry<String, float[]> entry : skillVectors.entrySet()) {
                double sim = cosineSimilarity(queryVector, entry.getValue());
                scores.add(Map.entry(entry.getKey(), sim));
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(topK, scores.size()); i++) {
                selected.add(scores.get(i).getKey());
            }
            selected.add("chitchat");

            List<SkillDefinition> result = new ArrayList<>();
            // 按 selected 的顺序（即相似度排序）遍历，保持正确的顺序
            Map<String, SkillDefinition> skillByName = allSkills.stream()
                    .collect(Collectors.toMap(SkillDefinition::name, s -> s));
            for (String skillName : selected) {
                SkillDefinition skill = skillByName.get(skillName);
                if (skill != null) {
                    result.add(skill);
                }
            }

            log.info("[SkillEmbeddingIndex] Embedding Top-{}: {} (from {} skills)",
                    topK, selected, allSkills.size());
            return result;
        } catch (Exception e) {
            log.warn("[SkillEmbeddingIndex] Embedding 检索失败，降级为词法 Top-K: {}", e.getMessage());
            return retrieveTopKByLexical(userMessage, allSkills, topK);
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

    private List<SkillDefinition> retrieveTopKByLexical(String userMessage, List<SkillDefinition> allSkills, int topK) {
        List<Map.Entry<String, Double>> scores = new ArrayList<>();
        for (SkillDefinition skill : allSkills) {
            scores.add(Map.entry(skill.name(), lexicalScore(userMessage, skill)));
        }
        scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Set<String> selected = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            selected.add(scores.get(i).getKey());
        }
        // 确保 chitchat 始终在候选中
        selected.add("chitchat");

        List<SkillDefinition> result = new ArrayList<>();
        Map<String, SkillDefinition> skillByName = allSkills.stream()
                .collect(Collectors.toMap(SkillDefinition::name, s -> s));
        for (String skillName : selected) {
            SkillDefinition skill = skillByName.get(skillName);
            if (skill != null) {
                result.add(skill);
            }
        }

        log.info("[SkillEmbeddingIndex] Lexical Top-{}: {} (from {} skills)",
                topK, selected, allSkills.size());
        return result;
    }

    private double lexicalScore(String userMessage, SkillDefinition skill) {
        String query = normalize(userMessage);
        String skillName = normalize(skill.name());
        String skillText = normalize(skill.name() + " " + skill.description());

        if (query.isEmpty() || skillText.isEmpty()) {
            return 0;
        }

        double score = 0;
        if (query.contains(skillName)) {
            score += 2.0;
        }
        if (skillText.contains(query)) {
            score += 3.0;
        }

        Set<String> queryTokens = tokenize(query);
        Set<String> skillTokens = tokenize(skillText);
        if (!queryTokens.isEmpty() && !skillTokens.isEmpty()) {
            int overlap = 0;
            for (String t : queryTokens) {
                if (skillTokens.contains(t)) {
                    overlap++;
                }
            }
            score += (double) overlap / Math.sqrt(queryTokens.size() * skillTokens.size());
        }

        Set<String> queryBigrams = toBigrams(query);
        Set<String> skillBigrams = toBigrams(skillText);
        if (!queryBigrams.isEmpty() && !skillBigrams.isEmpty()) {
            int inter = 0;
            for (String b : queryBigrams) {
                if (skillBigrams.contains(b)) {
                    inter++;
                }
            }
            int union = queryBigrams.size() + skillBigrams.size() - inter;
            if (union > 0) {
                score += (double) inter / union;
            }
        }
        return score;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).trim();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.split("[^\\p{IsHan}\\p{Alnum}]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Set<String> toBigrams(String text) {
        String normalized = text.replaceAll("\\s+", "");
        Set<String> grams = new LinkedHashSet<>();
        if (normalized.length() < 2) {
            return grams;
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 检索与用户消息最相关的 Top-K 个 Skill，返回带分数的结果
     */
    public List<SkillScore> retrieveTopKWithScores(String userMessage, int topK) {
        List<SkillDefinition> allSkills = skillLoader.getSkills();
        List<SkillScore> result = new ArrayList<>();

        if (!embeddingEnabled || !indexReady) {
            // 词法检索
            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (SkillDefinition skill : allSkills) {
                scores.add(Map.entry(skill.name(), lexicalScore(userMessage, skill)));
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Double> entry : scores) {
                SkillDefinition skill = allSkills.stream()
                        .filter(s -> s.name().equals(entry.getKey()))
                        .findFirst().orElse(null);
                if (skill != null) {
                    result.add(new SkillScore(skill, entry.getValue(), "lexical"));
                }
            }
            return result;
        }

        try {
            float[] queryVector = embeddingClient.embedSingleText(userMessage);
            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (Map.Entry<String, float[]> entry : skillVectors.entrySet()) {
                double sim = cosineSimilarity(queryVector, entry.getValue());
                scores.add(Map.entry(entry.getKey(), sim));
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            for (Map.Entry<String, Double> entry : scores) {
                SkillDefinition skill = allSkills.stream()
                        .filter(s -> s.name().equals(entry.getKey()))
                        .findFirst().orElse(null);
                if (skill != null) {
                    result.add(new SkillScore(skill, entry.getValue(), "embedding"));
                }
            }
        } catch (Exception e) {
            log.warn("[SkillEmbeddingIndex] Embedding 检索失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 带分数的 Skill 检索结果
     */
    public record SkillScore(SkillDefinition skill, double score, String method) {}

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
