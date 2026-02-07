package com.example.mcp.client.skill;

/**
 * Skill 执行响应
 */
public record SkillResponse(
        String skillName,
        String content
) {
}
