package com.example.mcp.client.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SKILL.md 加载器 —— 自动扫描 classpath:skills/ 目录下的所有 SKILL.md 文件，
 * 解析 YAML frontmatter + Markdown 正文，生成 SkillDefinition 列表。
 *
 * 新增 Skill 只需在 resources/skills/ 下创建一个目录和 SKILL.md 文件，零 Java 代码。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final List<SkillDefinition> skills;

    public SkillLoader() {
        this.skills = loadAllSkills();
    }

    public List<SkillDefinition> getSkills() {
        return skills;
    }

    private List<SkillDefinition> loadAllSkills() {
        List<SkillDefinition> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");

            for (Resource resource : resources) {
                try {
                    String content = new BufferedReader(
                            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                            .lines()
                            .collect(Collectors.joining("\n"));

                    String location = resource.getURI().toString();
                    SkillDefinition skill = parseMetadata(content, location);
                    if (skill != null) {
                        result.add(skill);
                        log.info("[SkillLoader] 加载 Skill: [{}] {} (工具: {}, 路径: {})",
                                skill.name(), skill.description(), skill.allowedTools(), skill.location());
                    }
                } catch (Exception e) {
                    log.error("[SkillLoader] 加载 SKILL.md 失败: {}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.error("[SkillLoader] 扫描 skills 目录失败", e);
        }

        log.info("[SkillLoader] 共加载 {} 个 Skills（仅 metadata）", result.size());
        return result;
    }

    /**
     * 解析 SKILL.md 的 frontmatter（仅 metadata，不加载 body）
     * 遵循 agentskills.io Progressive Disclosure：启动时只加载 ~100 tokens 的 metadata
     */
    private SkillDefinition parseMetadata(String content, String location) {
        if (!content.startsWith("---")) {
            log.warn("[SkillLoader] SKILL.md 缺少 frontmatter: {}", location);
            return null;
        }

        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            log.warn("[SkillLoader] SKILL.md frontmatter 未闭合: {}", location);
            return null;
        }

        String frontmatter = content.substring(3, endIdx).trim();

        String name = extractYamlValue(frontmatter, "name");
        String description = extractYamlValue(frontmatter, "description");
        List<String> tools = extractAllowedTools(frontmatter);

        if (name == null || name.isBlank()) {
            log.warn("[SkillLoader] SKILL.md 缺少 name 字段: {}", location);
            return null;
        }

        return new SkillDefinition(name, description != null ? description : "", tools, location);
    }

    /**
     * 按需加载 SKILL.md 的 body（System Prompt）—— Skill 被激活时才调用
     * 遵循 agentskills.io Progressive Disclosure：Instructions 在 Skill 激活时加载
     */
    public String loadPrompt(SkillDefinition skill) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource(skill.location());
            String content = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            int endIdx = content.indexOf("---", 3);
            if (endIdx < 0) {
                log.error("[SkillLoader] 加载 body 失败，frontmatter 未闭合: {}", skill.location());
                return "";
            }
            String prompt = content.substring(endIdx + 3).trim();
            log.debug("[SkillLoader] 按需加载 Skill [{}] body，长度: {}", skill.name(), prompt.length());
            return prompt;
        } catch (Exception e) {
            log.error("[SkillLoader] 按需加载 Skill [{}] body 失败: {}", skill.name(), e.getMessage(), e);
            return "";
        }
    }

    private String extractYamlValue(String yaml, String key) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String value = trimmed.substring(key.length() + 1).trim();
                // 去掉引号
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    /**
     * 解析 allowed-tools 字段（agentskills.io 规范：空格分隔的工具名字符串）
     */
    private List<String> extractAllowedTools(String frontmatter) {
        String value = extractYamlValue(frontmatter, "allowed-tools");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\s,]+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }
}
