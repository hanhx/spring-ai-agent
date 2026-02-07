package com.example.mcp.client.skill;

import java.util.List;

/**
 * Skill 定义 —— 从 SKILL.md 文件解析而来（仅 metadata，body 延迟加载）
 *
 * 遵循 agentskills.io 的 Progressive Disclosure 规范：
 * - 启动时只加载 metadata（name, description, tools, location）
 * - Skill 被激活时才按需加载 SKILL.md body（System Prompt）
 *
 * @param name        Skill 名称（来自 frontmatter，小写字母+连字符）
 * @param description Skill 描述（来自 frontmatter，供 Router 做意图分发）
 * @param allowedTools 该 Skill 预批准的 MCP 工具名称列表（来自 frontmatter allowed-tools）
 * @param location     SKILL.md 文件的资源路径（如 classpath:skills/weather/SKILL.md）
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> allowedTools,
        String location
) {
}
