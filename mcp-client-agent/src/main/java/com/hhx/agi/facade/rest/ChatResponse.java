package com.hhx.agi.facade.rest;

import com.hhx.agi.application.agent.SkillResponse;

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
