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

                    SkillDefinition skill = parse(content);
                    if (skill != null) {
                        result.add(skill);
                        log.info("[SkillLoader] 加载 Skill: [{}] {} (工具: {})",
                                skill.name(), skill.description(), skill.tools());
                    }
                } catch (Exception e) {
                    log.error("[SkillLoader] 加载 SKILL.md 失败: {}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.error("[SkillLoader] 扫描 skills 目录失败", e);
        }

        log.info("[SkillLoader] 共加载 {} 个 Skills", result.size());
        return result;
    }

    /**
     * 解析 SKILL.md 文件内容：YAML frontmatter + Markdown 正文
     */
    private SkillDefinition parse(String content) {
        if (!content.startsWith("---")) {
            log.warn("[SkillLoader] SKILL.md 缺少 frontmatter");
            return null;
        }

        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            log.warn("[SkillLoader] SKILL.md frontmatter 未闭合");
            return null;
        }

        String frontmatter = content.substring(3, endIdx).trim();
        String prompt = content.substring(endIdx + 3).trim();

        String name = extractYamlValue(frontmatter, "name");
        String description = extractYamlValue(frontmatter, "description");
        List<String> tools = extractYamlList(frontmatter, "tools");

        if (name == null || name.isBlank()) {
            log.warn("[SkillLoader] SKILL.md 缺少 name 字段");
            return null;
        }

        return new SkillDefinition(name, description != null ? description : "", tools, prompt);
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

    private List<String> extractYamlList(String yaml, String key) {
        List<String> result = new ArrayList<>();
        boolean inList = false;

        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String inline = trimmed.substring(key.length() + 1).trim();
                // 处理内联空列表 tools: []
                if ("[]".equals(inline)) {
                    return result;
                }
                // 处理内联列表 tools: [a, b]
                if (inline.startsWith("[") && inline.endsWith("]")) {
                    String items = inline.substring(1, inline.length() - 1);
                    for (String item : items.split(",")) {
                        String val = item.trim();
                        if (!val.isBlank()) result.add(val);
                    }
                    return result;
                }
                inList = true;
                continue;
            }
            if (inList) {
                if (trimmed.startsWith("- ")) {
                    result.add(trimmed.substring(2).trim());
                } else {
                    break;
                }
            }
        }
        return result;
    }
}
