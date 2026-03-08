package com.hhx.agi.application.agent;

import com.hhx.agi.infra.dao.SkillRegistryMapper;
import com.hhx.agi.infra.po.SkillRegistryPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 加载器 —— 从数据库加载 SkillDefinition 列表。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final SkillRegistryMapper skillRegistryMapper;
    private List<SkillDefinition> skills;
    private final Map<String, String> dbPrompts = new LinkedHashMap<>();

    public SkillLoader(SkillRegistryMapper skillRegistryMapper) {
        this.skillRegistryMapper = skillRegistryMapper;
        this.skills = loadAllSkills();
    }

    public List<SkillDefinition> getSkills() {
        return skills;
    }

    /**
     * 重新加载所有 Skills（用于动态更新）
     */
    public void reload() {
        this.skills = loadAllSkills();
        log.info("[SkillLoader] Skills 重新加载完成，共 {} 个", skills.size());
    }

    private List<SkillDefinition> loadAllSkills() {
        List<SkillDefinition> dbSkills = loadFromDatabase();

        log.info("[SkillLoader] 共加载 {} 个 Skills", dbSkills.size());
        return dbSkills;
    }

    private List<SkillDefinition> loadFromDatabase() {
        List<SkillDefinition> result = new ArrayList<>();
        try {
            List<SkillRegistryPO> dbSkills = skillRegistryMapper.selectEnabledOrderByPriority(true);

            for (SkillRegistryPO po : dbSkills) {
                String name = po.getName();
                String description = po.getDescription();
                String allowedToolsRaw = po.getAllowedTools();
                String promptBody = po.getPromptBody();

                if (promptBody != null) {
                    dbPrompts.put(name, promptBody);
                }
                result.add(new SkillDefinition(
                        name,
                        description != null ? description : "",
                        parseAllowedToolsRaw(allowedToolsRaw),
                        "db:" + name
                ));
            }

            log.info("[SkillLoader] DB 加载 {} 个 Skills", result.size());
        } catch (Exception e) {
            // 首次启动/未建表场景可平滑降级到 classpath
            log.warn("[SkillLoader] DB 加载 Skills 失败，降级为 classpath-only: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载 Skill 的 prompt（从数据库）
     */
    public String loadPrompt(SkillDefinition skill) {
        String prompt = dbPrompts.get(skill.name());
        if (prompt == null || prompt.isBlank()) {
            log.warn("[SkillLoader] Skill [{}] 缺少 prompt_body", skill.name());
            return "";
        }
        return prompt;
    }

    private List<String> parseAllowedToolsRaw(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }
}
