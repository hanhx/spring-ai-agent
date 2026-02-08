package com.example.mcp.client.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "conversationId 不能为空") String conversationId,
        @NotBlank(message = "消息不能为空") String message
) {
}
