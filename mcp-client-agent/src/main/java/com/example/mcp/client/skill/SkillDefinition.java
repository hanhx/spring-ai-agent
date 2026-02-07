package com.example.mcp.client.skill;

import java.util.List;

/**
 * Skill 定义 —— 从 SKILL.md 文件解析而来
 *
 * @param name        Skill 名称（来自 frontmatter）
 * @param description Skill 描述（来自 frontmatter，供 Router 做意图分发）
 * @param tools       该 Skill 可使用的 MCP 工具名称列表（来自 frontmatter）
 * @param prompt      Skill 的 System Prompt（SKILL.md 正文内容）
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> tools,
        String prompt
) {
}
