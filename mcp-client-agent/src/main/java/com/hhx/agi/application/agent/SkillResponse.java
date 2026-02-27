package com.hhx.agi.application.agent;

/**
 * Skill 执行响应
 */
public record SkillResponse(
        String skillName,
        String content
) {
}
