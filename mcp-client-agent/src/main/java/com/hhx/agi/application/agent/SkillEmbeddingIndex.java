package com.hhx.agi.application.agent;

import com.hhx.agi.infra.client.DashScopeEmbeddingClient;
import com.hhx.agi.infra.dao.SkillRegistryMapper;
import com.hhx.agi.infra.po.SkillRegistryPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 向量检索
 * 查询时从数据库读取向量，在 Java 层计算相似度
 */
@Component
public class SkillEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingIndex.class);

    private static final int DEFAULT_TOP_K = 5;
    private final DashScopeEmbeddingClient embeddingClient;
    private final SkillLoader skillLoader;
    private final SkillRegistryMapper skillRegistryMapper;

    @Value("${dashscope.embedding.enabled:true}")
    private boolean embeddingEnabled;

    public SkillEmbeddingIndex(
            DashScopeEmbeddingClient embeddingClient,
            SkillLoader skillLoader,
            SkillRegistryMapper skillRegistryMapper) {
        this.embeddingClient = embeddingClient;
        this.skillLoader = skillLoader;
        this.skillRegistryMapper = skillRegistryMapper;
    }

    /**
     * 检索与用户消息最相关的 Top-K 个 Skill
     */
    public List<SkillDefinition> retrieveTopK(String userMessage, int topK) {
        List<SkillDefinition> allSkills = skillLoader.getSkills();

        if (allSkills.size() <= topK) {
            return allSkills;
        }

        if (!embeddingEnabled) {
            return retrieveTopKByLexical(userMessage, allSkills, topK);
        }

        try {
            // 生成查询向量
            float[] queryVector = embeddingClient.embedSingleText(userMessage);

            // 从数据库查询有向量的 skill
            List<SkillRegistryPO> skillsWithVector = skillRegistryMapper.selectEnabledWithVector(true);

            if (skillsWithVector.isEmpty()) {
                log.warn("[SkillEmbeddingIndex] 数据库没有向量数据，降级为词法匹配");
                return retrieveTopKByLexical(userMessage, allSkills, topK);
            }

            // 在 Java 层计算相似度并排序
            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (SkillRegistryPO po : skillsWithVector) {
                float[] embedding = po.getEmbedding();
                if (embedding != null && embedding.length > 0) {
                    double sim = cosineSimilarity(queryVector, embedding);
                    scores.add(Map.entry(po.getName(), sim));
                }
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            // 取 Top-K
            Set<String> topNames = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(topK, scores.size()); i++) {
                topNames.add(scores.get(i).getKey());
            }
            topNames.add("chitchat");

            // 转换为 SkillDefinition
            List<SkillDefinition> result = new ArrayList<>();
            Map<String, SkillDefinition> skillByName = allSkills.stream()
                    .collect(Collectors.toMap(SkillDefinition::name, s -> s, (a, b) -> a));

            for (String name : topNames) {
                SkillDefinition skill = skillByName.get(name);
                if (skill != null) {
                    result.add(skill);
                }
            }

            log.info("[SkillEmbeddingIndex] 向量 Top-{}: {}", result.size(), topNames);
            return result;
        } catch (Exception e) {
            log.warn("[SkillEmbeddingIndex] 向量查询失败，降级为词法匹配: {}", e.getMessage());
            return retrieveTopKByLexical(userMessage, allSkills, topK);
        }
    }

    /**
     * 使用默认 Top-K
     */
    public List<SkillDefinition> retrieveTopK(String userMessage) {
        return retrieveTopK(userMessage, DEFAULT_TOP_K);
    }

    /**
     * 词法匹配（降级方案）
     */
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
        selected.add("chitchat");

        List<SkillDefinition> result = new ArrayList<>();
        Map<String, SkillDefinition> skillByName = allSkills.stream()
                .collect(Collectors.toMap(SkillDefinition::name, s -> s, (a, b) -> a));
        for (String skillName : selected) {
            SkillDefinition skill = skillByName.get(skillName);
            if (skill != null) {
                result.add(skill);
            }
        }

        log.info("[SkillEmbeddingIndex] Lexical Top-{}: {}", result.size(), selected);
        return result;
    }

    /**
     * 检索带分数的结果（用于测试）
     */
    public List<SkillScore> retrieveTopKWithScores(String userMessage, int topK) {
        List<SkillDefinition> allSkills = skillLoader.getSkills();
        List<SkillScore> result = new ArrayList<>();

        if (!embeddingEnabled) {
            return retrieveTopKByLexicalWithScores(userMessage, allSkills, topK);
        }

        try {
            float[] queryVector = embeddingClient.embedSingleText(userMessage);
            List<SkillRegistryPO> skillsWithVector = skillRegistryMapper.selectEnabledWithVector(true);

            List<Map.Entry<String, Double>> scores = new ArrayList<>();
            for (SkillRegistryPO po : skillsWithVector) {
                float[] embedding = po.getEmbedding();
                if (embedding != null && embedding.length > 0) {
                    double sim = cosineSimilarity(queryVector, embedding);
                    scores.add(Map.entry(po.getName(), sim));
                }
            }
            scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            Map<String, SkillDefinition> skillByName = allSkills.stream()
                    .collect(Collectors.toMap(SkillDefinition::name, s -> s, (a, b) -> a));

            for (int i = 0; i < Math.min(topK, scores.size()); i++) {
                String name = scores.get(i).getKey();
                SkillDefinition skill = skillByName.get(name);
                if (skill != null) {
                    result.add(new SkillScore(skill, scores.get(i).getValue(), "embedding"));
                }
            }

            // 添加 chitchat 兜底
            SkillDefinition chitchat = skillByName.get("chitchat");
            if (chitchat != null && result.stream().noneMatch(s -> s.skill().name().equals("chitchat"))) {
                result.add(new SkillScore(chitchat, 0.0, "fallback"));
            }
        } catch (Exception e) {
            log.warn("[SkillEmbeddingIndex] 向量查询失败: {}", e.getMessage());
            return retrieveTopKByLexicalWithScores(userMessage, allSkills, topK);
        }
        return result;
    }

    private List<SkillScore> retrieveTopKByLexicalWithScores(String userMessage, List<SkillDefinition> allSkills, int topK) {
        List<SkillScore> result = new ArrayList<>();
        List<Map.Entry<String, Double>> scores = new ArrayList<>();
        for (SkillDefinition skill : allSkills) {
            scores.add(Map.entry(skill.name(), lexicalScore(userMessage, skill)));
        }
        scores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Map<String, SkillDefinition> skillByName = allSkills.stream()
                .collect(Collectors.toMap(SkillDefinition::name, s -> s, (a, b) -> a));

        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            String name = scores.get(i).getKey();
            SkillDefinition skill = skillByName.get(name);
            if (skill != null) {
                result.add(new SkillScore(skill, scores.get(i).getValue(), "lexical"));
            }
        }
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
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }

    /**
     * 带分数的 Skill 检索结果
     */
    public record SkillScore(SkillDefinition skill, double score, String method) {}

    /**
     * 检查向量索引是否可用
     */
    public boolean isIndexReady() {
        return embeddingEnabled;
    }
}