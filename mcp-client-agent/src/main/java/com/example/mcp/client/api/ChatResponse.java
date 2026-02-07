package com.example.mcp.client.api;

import com.example.mcp.client.skill.SkillResponse;

public record ChatResponse(
        String conversationId,
        String skillName,
        String content
) {
    public static ChatResponse from(String conversationId, SkillResponse skillResponse) {
        return new ChatResponse(
                conversationId,
                skillResponse.skillName(),
                skillResponse.content()
        );
    }
}
