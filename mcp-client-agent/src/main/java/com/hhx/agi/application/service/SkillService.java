package com.hhx.agi.application.service;

import com.hhx.agi.application.agent.SkillEmbeddingIndex;
import com.hhx.agi.infra.client.DashScopeEmbeddingClient;
import com.hhx.agi.infra.dao.SkillRegistryMapper;
import com.hhx.agi.infra.handler.VectorTypeHandler;
import com.hhx.agi.infra.po.SkillRegistryPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Skill 管理服务
 * 支持动态 CRUD、向量生成和存储
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final SkillRegistryMapper skillRegistryMapper;
    private final SkillEmbeddingIndex skillEmbeddingIndex;
    private final DashScopeEmbeddingClient embeddingClient;

    public SkillService(
            SkillRegistryMapper skillRegistryMapper,
            SkillEmbeddingIndex skillEmbeddingIndex,
            DashScopeEmbeddingClient embeddingClient) {
        this.skillRegistryMapper = skillRegistryMapper;
        this.skillEmbeddingIndex = skillEmbeddingIndex;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 获取所有 Skills
     */
    public List<SkillRegistryPO> listAll() {
        return skillRegistryMapper.selectList(null);
    }

    /**
     * 获取启用的 Skills
     */
    public List<SkillRegistryPO> listEnabled() {
        return skillRegistryMapper.selectEnabledOrderByPriority(true);
    }

    /**
     * 根据 ID 获取 Skill
     */
    public SkillRegistryPO getById(Long id) {
        return skillRegistryMapper.selectById(id);
    }

    /**
     * 根据名称获取 Skill
     */
    public SkillRegistryPO getByName(String name) {
        return skillRegistryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SkillRegistryPO>()
                        .eq(SkillRegistryPO::getName, name)
        ).stream().findFirst().orElse(null);
    }

    /**
     * 创建新 Skill（自动生成向量）
     */
    @Transactional
    public SkillRegistryPO create(SkillRegistryPO skill) {
        // 生成向量
        float[] embedding = generateEmbedding(skill.getName(), skill.getDescription());
        skill.setEmbedding(embedding);

        // 设置默认值
        if (skill.getEnabled() == null) {
            skill.setEnabled(true);
        }
        if (skill.getPriority() == null) {
            skill.setPriority(0);
        }

        skillRegistryMapper.insert(skill);
        log.info("[SkillService] 创建 Skill: id={}, name={}", skill.getId(), skill.getName());

        return skill;
    }

    /**
     * 更新 Skill（自动重新生成向量）
     */
    @Transactional
    public SkillRegistryPO update(Long id, SkillRegistryPO updates) {
        SkillRegistryPO existing = skillRegistryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Skill not found: " + id);
        }

        // 更新字段
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getAllowedTools() != null) {
            existing.setAllowedTools(updates.getAllowedTools());
        }
        if (updates.getPromptBody() != null) {
            existing.setPromptBody(updates.getPromptBody());
        }
        if (updates.getEnabled() != null) {
            existing.setEnabled(updates.getEnabled());
        }
        if (updates.getPriority() != null) {
            existing.setPriority(updates.getPriority());
        }

        // 如果名称或描述变更，重新生成向量
        if (updates.getName() != null || updates.getDescription() != null) {
            float[] embedding = generateEmbedding(existing.getName(), existing.getDescription());
            existing.setEmbedding(embedding);
        }

        skillRegistryMapper.updateById(existing);
        log.info("[SkillService] 更新 Skill: id={}, name={}", id, existing.getName());

        return existing;
    }

    /**
     * 删除 Skill
     */
    @Transactional
    public void delete(Long id) {
        skillRegistryMapper.deleteById(id);
        log.info("[SkillService] 删除 Skill: id={}", id);
    }

    /**
     * 切换 Skill 启用状态
     */
    @Transactional
    public SkillRegistryPO toggleEnabled(Long id) {
        SkillRegistryPO skill = skillRegistryMapper.selectById(id);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + id);
        }

        skill.setEnabled(!skill.getEnabled());
        skillRegistryMapper.updateById(skill);
        log.info("[SkillService] 切换 Skill 状态: id={}, enabled={}", id, skill.getEnabled());

        return skill;
    }

    /**
     * 为所有 skills 重新生成向量
     */
    @Transactional
    public void regenerateAllVectors() {
        log.info("[SkillService] 开始重新生成所有向量...");
        List<SkillRegistryPO> skills = skillRegistryMapper.selectList(null);
        int successCount = 0;
        int failCount = 0;
        for (SkillRegistryPO skill : skills) {
            try {
                float[] embedding = generateEmbedding(skill.getName(), skill.getDescription());
                if (embedding != null && embedding.length > 0) {
                    skill.setEmbedding(embedding);
                    skillRegistryMapper.updateById(skill);
                    successCount++;
                    log.info("[SkillService] Skill [{}] 向量生成成功，维度: {}", skill.getName(), embedding.length);
                } else {
                    failCount++;
                    log.warn("[SkillService] Skill [{}] 向量生成返回 null", skill.getName());
                }
            } catch (Exception e) {
                failCount++;
                log.warn("[SkillService] Skill [{}] 向量生成失败: {}", skill.getName(), e.getMessage());
            }
        }
        log.info("[SkillService] 向量生成完成: 成功 {}, 失败 {}, 总数 {}", successCount, failCount, skills.size());
    }

    /**
     * 测试向量匹配
     */
    public List<SkillEmbeddingIndex.SkillScore> testMatch(String query, int topK) {
        return skillEmbeddingIndex.retrieveTopKWithScores(query, topK);
    }

    /**
     * 生成向量
     */
    private float[] generateEmbedding(String name, String description) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String text = name + ": " + (description != null ? description : "");
        try {
            return embeddingClient.embedSingleText(text);
        } catch (Exception e) {
            log.warn("[SkillService] 向量生成失败: {}", e.getMessage());
            return null;
        }
    }
}