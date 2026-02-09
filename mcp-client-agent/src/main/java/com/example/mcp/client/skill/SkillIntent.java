package com.example.mcp.client.skill;

/**
 * 多意图识别结果 —— 一个用户消息可能包含多个意图，每个意图对应一个 Skill 和子任务描述
 *
 * @param skillName Skill 名称（如 weather、order-query）
 * @param subTask   该意图对应的子任务描述（如"查询北京天气"、"查询订单ORD123"）
 */
public record SkillIntent(
        String skillName,
        String subTask
) {
}
