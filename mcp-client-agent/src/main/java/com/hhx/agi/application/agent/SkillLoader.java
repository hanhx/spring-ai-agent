package com.hhx.agi.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final JdbcTemplate jdbc;
    private final List<SkillDefinition> skills;
    private final Map<String, String> dbPrompts = new LinkedHashMap<>();

    public SkillLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.skills = loadAllSkills();
    }

    public List<SkillDefinition> getSkills() {
        return skills;
    }

    private List<SkillDefinition> loadAllSkills() {
        List<SkillDefinition> classpathSkills = loadFromClasspath();
        List<SkillDefinition> dbSkills = loadFromDatabase();

        // 按 name 去重合并：DB 优先覆盖 classpath（便于线上动态调整）
        Map<String, SkillDefinition> merged = new LinkedHashMap<>();
        classpathSkills.forEach(skill -> merged.put(skill.name(), skill));
        dbSkills.forEach(skill -> merged.put(skill.name(), skill));

        List<SkillDefinition> result = new ArrayList<>(merged.values());
        log.info("[SkillLoader] 共加载 {} 个 Skills（classpath: {}, db: {}）",
                result.size(), classpathSkills.size(), dbSkills.size());
        return result;
    }

    private List<SkillDefinition> loadFromClasspath() {
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

        log.info("[SkillLoader] classpath 加载 {} 个 Skills", result.size());
        return result;
    }

    private List<SkillDefinition> loadFromDatabase() {
        List<SkillDefinition> result = new ArrayList<>();
        try {
            String sql = """
                    SELECT name, description, allowed_tools, prompt_body
                    FROM skill_registry
                    WHERE enabled = TRUE
                    ORDER BY priority DESC, id ASC
                    """;

            result = jdbc.query(sql, (rs, i) -> {
                String name = rs.getString("name");
                String description = rs.getString("description");
                String allowedToolsRaw = rs.getString("allowed_tools");
                String promptBody = rs.getString("prompt_body");

                if (promptBody != null) {
                    dbPrompts.put(name, promptBody);
                }
                return new SkillDefinition(
                        name,
                        description != null ? description : "",
                        parseAllowedToolsRaw(allowedToolsRaw),
                        "db:" + name
                );
            });

            log.info("[SkillLoader] DB 加载 {} 个 Skills", result.size());
        } catch (Exception e) {
            // 首次启动/未建表场景可平滑降级到 classpath
            log.warn("[SkillLoader] DB 加载 Skills 失败，降级为 classpath-only: {}", e.getMessage());
        }
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
        if (skill.location() != null && skill.location().startsWith("db:")) {
            String prompt = dbPrompts.get(skill.name());
            if (prompt == null || prompt.isBlank()) {
                log.warn("[SkillLoader] DB Skill [{}] 缺少 prompt_body", skill.name());
                return "";
            }
            return prompt;
        }

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
